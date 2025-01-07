package com.example.transaction;

import com.example.mock.*;
import kalix.javasdk.annotations.Id;
import kalix.javasdk.annotations.TypeId;
import kalix.javasdk.client.ComponentClient;
import kalix.javasdk.workflow.Workflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static com.example.transaction.TransactionWorkflow.State.Status.*;
import static com.example.transaction.Transaction.Response.*;
import static com.example.mock.Validation.Validate;
import static com.example.mock.Validation.ValidationResult;
import static com.example.mock.Sanction.Check;
import static com.example.mock.Sanction.SanctionResult;
import static com.example.mock.Liquidity.LiquidityResult;
import static java.time.Duration.ofSeconds;

@TypeId("transaction")
@Id("txId")
@RequestMapping("/transaction/{txId}")
public class TransactionWorkflow extends Workflow<TransactionWorkflow.State> {

    private static final Logger log = LoggerFactory.getLogger(TransactionWorkflow.class);

    final private ComponentClient client;

    public TransactionWorkflow(ComponentClient client) {
        this.client = client;
    }

    @Override
    public WorkflowDef<State> definition() {

        Step validationCheck = step("validate-transaction")
            .call(Validate.Transaction.class, cmd -> {
                log.info("Validating Payment Request: " + cmd);
                return client.forAction()
                    .call(Validation::validate)
                    .params(cmd);
            })
            .andThen(ValidationResult.class, validationResult -> switch(validationResult) {
                case ValidationResult.Approved __ -> {
                    var state = currentState();
                    var sanctionCheck = new Check.Accounts(
                        state.txId(),
                        state.transaction().from(), //checking source account
                        state.transaction().to()    //checking destination account
                    );
                    log.info("Validation Request Approved: " + state.txId());
                    yield effects()
                        .updateState(
                            state.logStep("validate-transaction", "approved")
                                 .withStatus(CHECKING_SANCTIONS)
                        )
                        .transitionTo("sanction-check", sanctionCheck);
                }
                case ValidationResult.Rejected rejected -> {
                    log.warn("Validation Request Rejected: " + rejected.reason());
                    yield effects()
                        .updateState(
                            currentState()
                                .logStep("validate-transaction", "rejected")
                                .withStatus(VALIDATION_FAILED)
                        )
                        .end();
                }
            });

        Step sanctionCheck = step("sanction-check")
            .call(Check.Accounts.class, cmd -> {
                log.info("Checking Sanctions: " + cmd);
                return client.forAction()
                    .call(Sanction::check)
                    .params(cmd);
            })
            .andThen(SanctionResult.class, sanctionResult -> switch(sanctionResult) {
                case SanctionResult.Approved __ -> {
                    var state = currentState();
                    var liquidityCheck = new Liquidity.Verify.Funds(
                        state.txId(),
                        state.transaction().from(),
                        state.transaction().amount()
                    );
                    log.info("Sanction Check Approved");
                    yield effects()
                        .updateState(
                            state.logStep("sanction-check", "approved")
                                 .withStatus(VERIFYING_LIQUIDITY)
                        )
                        .transitionTo("liquidity-check", liquidityCheck);
                }
                case SanctionResult.Rejected rejected -> {
                    log.warn("Sanction Check Rejected: " + rejected.reason());
                    yield effects()
                        .updateState(
                            currentState()
                                .logStep("sanction-check", "rejected")
                                .withStatus(SANCTIONS_FAILED)
                        )
                        .end();
                }
            });

        Step liquidityCheck = step("liquidity-check")
            .call(Liquidity.Verify.Funds.class, cmd -> {
                log.info("Verifying Liquidity: " + cmd);
                return client.forAction()
                    .call(Liquidity::verify)
                    .params(cmd);
            })
            .andThen(LiquidityResult.class, liquidityResult -> switch(liquidityResult) {
                case LiquidityResult.Approved __ -> {
                    var state = currentState();
                    var postFunds = new Posting.Post.Funds(
                        state.txId(),
                        state.transaction().from(),
                        state.transaction().amount()
                    );
                    log.info("Liquidity Check Approved");
                    yield effects()
                        .updateState(
                            state.logStep("liquidity-check", "approved")
                                 .withStatus(POSTING_TRANSACTION)
                        )
                        .transitionTo("posting-transaction", postFunds);
                }
                case LiquidityResult.Rejected rejected -> {
                    log.warn("Liquidity Check Rejected: " + rejected.reason());
                    yield effects()
                        .updateState(
                            currentState()
                                .logStep("liquidity-check", "rejected")
                                .withStatus(LIQUIDITY_FAILED)
                        )
                        .end();
                }
            });

        Step posting = step("posting-transaction")
            .call(Posting.Post.Funds.class, cmd -> {
                log.info("Posting Transaction: " + cmd);
                return client.forAction()
                    .call(Posting::post)
                    .params(cmd);
            })
            .andThen(Posting.PostResult.class, postingResult -> switch(postingResult) {
                case Posting.PostResult.Approved __ -> {
                    var state = currentState();
                    var clearing = new Clearing.Clear.Funds(
                        state.txId(),
                        state.transaction().to(),
                        state.transaction().amount()
                    );
                    log.info("Transaction Posted");
                    yield effects()
                        .updateState(
                            currentState()
                                .logStep("posting-transaction", "approved")
                                .withStatus(CLEARING_TRANSACTION)
                        )
                        .transitionTo("transaction-clearing", clearing);
                }
                case Posting.PostResult.Rejected rejected -> {
                    log.warn("Transaction Posting Rejected: " + rejected.reason());
                    yield effects()
                        .updateState(
                            currentState()
                                .logStep("posting-transaction", "rejected")
                                .withStatus(POSTING_FAILED)
                        )
                        .end();
                }
            });

        Step clearing = step("transaction-clearing")
            .call(Clearing.Clear.Funds.class, cmd -> {
                log.info("Clearing Transaction: " + cmd);
                return client.forAction()
                    .call(Clearing::clear)
                    .params(cmd);
            })
            .andThen(Clearing.ClearingResult.class, clearingResult -> switch(clearingResult) {
                case Clearing.ClearingResult.Accepted __ -> {
                    log.info("Transaction Cleared");
                    yield effects()
                        .updateState(
                            currentState()
                                .logStep("transaction-clearing", "approved")
                                .complete()
                                .withStatus(TRANSACTION_COMPLETED)
                        )
                        .end();
                }
                case Clearing.ClearingResult.Rejected rejected -> {
                    var state = currentState();
                    var reversal = new Posting.Post.Reversal(
                        state.txId(),
                        state.transaction().from(),
                        state.transaction().amount()
                    );
                    log.warn("Transaction Clearing Rejected: " + rejected.reason());
                    yield effects()
                        .updateState(
                            currentState()
                                .logStep("transaction-clearing", "rejected")
                                .withStatus(CLEARING_FAILED)
                        )
                        .transitionTo("compensate", reversal);
                }
            });

        Step compensate = step("compensate")
            .call(Posting.Post.Reversal.class, cmd -> {
                log.info("Compensation");
                return client.forAction()
                    .call(Posting::reversal)
                    .params(cmd);
            })
            .andThen(Posting.PostResult.class, postingResult -> switch(postingResult) {
                case Posting.PostResult.Approved __ -> {
                    log.info("Compensation completed");
                    yield effects()
                        .updateState(
                            currentState()
                                .logStep("compensate", "approved")
                                .complete()
                                .withStatus(COMPENSATION_COMPLETED)
                        )
                        .end();
                }
                case Posting.PostResult.Rejected rejected -> {
                    log.warn("Compensation failed: " + rejected.reason());
                    yield effects()
                        .updateState(
                            currentState()
                                .logStep("compensate", "rejected")
                                .complete()
                                .withStatus(TRANSACTION_FAILED)
                        )
                        .end();
                }
            });

        Step failoverHandler = step("failover-handler")
            .asyncCall(() -> {
                log.info("Running workflow failed step for txId: " + currentState().txId());
                return CompletableFuture.completedStage("handling failure...");
            })
            .andThen(String.class, __ -> effects()
                .updateState(
                    currentState()
                        .logStep("failover-handler", "handling failure")
                        .complete()
                        .withStatus(TRANSACTION_FAILED)
                )
                .end())
            .timeout(ofSeconds(1));

        return workflow()
            .timeout(ofSeconds(60))
            .defaultStepTimeout(ofSeconds(30))
            .failoverTo("failover-handler", maxRetries(0))
            .defaultStepRecoverStrategy(maxRetries(1).failoverTo("failover-handler"))
            .addStep(validationCheck)
            .addStep(sanctionCheck)
            .addStep(liquidityCheck)
            .addStep(posting)
            .addStep(clearing, maxRetries(2).failoverTo("compensate"))
            .addStep(compensate)
            .addStep(failoverHandler);
    }

    /**
     * By virtue of using the Workflow class with a provided transactionId, any
     * duplicate transaction request, will come to the same workflow, and we can
     * determine the transaction status based on the current state of the workflow.
     */

    //TODO: Determine why the incoming JSON request isn't mapping to the Payment object
    /*
    @PostMapping
    public Effect<Response> process(@RequestBody Payment request) {
        var txId = commandContext().workflowId();
        var current = currentState();

        if (current != null) return effects().reply(respond(current, Status.DUPLICATE));

        var initialized = State.from(txId, request).withStatus(VALIDATING_REQUEST);
        var validateRequest = new Validate.Transaction(
            txId,
            request.from(),
            request.to(),
            request.amount()
        );

        return effects()
            .updateState(initialized)
            .transitionTo("validate-transaction", validateRequest)
            .thenReply(respond(initialized, Status.OK));
    }
    */

    /*
    * This is a workaround to the above method, where the incoming JSON request isn't mapping
    * to the Payment object
    * */
    @PostMapping("/process/{from}/{to}/{amount}")
    public Effect<Response> process(@PathVariable String from, @PathVariable String to, @PathVariable int amount) {
        var txId = commandContext().workflowId();
        var current = currentState();

        if (current != null) return effects().reply(respond(current, Status.DUPLICATE));

        //work around to the above method
        var paymentRequest = new Payment(from, to, "na", amount);
        var initialized = State.from(txId, paymentRequest).withStatus(VALIDATING_REQUEST);
        var validateRequest = new Validate.Transaction(
            txId,
            from,
            to,
            amount
        );

        return effects()
            .updateState(initialized)
            .transitionTo("validate-transaction", validateRequest)
            .thenReply(respond(initialized, Status.OK));
    }

    @GetMapping
    public Effect<State> getTransaction() {
        if (currentState() == null) {
            return effects().error("transaction not started");
        } else {
            return effects().reply(currentState());
        }
    }

    public record Payment(String from, String to, String sequence, int amount) {}

    private static Response respond(State state, Status status) {
        return switch(status) {
            case OK -> new Received(state.txId(), state.status().name(), state.started());
            case DUPLICATE -> new Processing(state.txId(), state.status().name(), "Duplicate Request: Transaction already handled.");
            case ERROR -> new Processing(state.txId(), state.status().name(), "Transaction failed.");
        };
    }

    private enum Status {
        OK,
        DUPLICATE,
        ERROR
    }

    public record State(
        String txId,
        Transaction transaction,
        Status status,
        Long started,
        Long ended,
        Long duration,
        StepStack history
    ) {

        public record Transaction(String from, String to, int amount) {}

        public record StepStack(List<StepEntry> steps) {

            public StepStack() { this(List.of()); }

            public StepStack push(StepEntry step) {
                return new StepStack(Stream.concat(steps.stream(), Stream.of(step)).toList());
            }

        }

        public record StepEntry(String name, String status, Long finished) {}

        public enum Status {
            VALIDATING_REQUEST,
            VALIDATION_FAILED,
            VERIFYING_LIQUIDITY,
            LIQUIDITY_FAILED,
            POSTING_TRANSACTION,
            POSTING_FAILED,
            CLEARING_TRANSACTION,
            CLEARING_FAILED,
            CHECKING_SANCTIONS,
            SANCTIONS_FAILED,
            TRANSACTION_COMPLETED,
            TRANSACTION_FAILED,
            COMPENSATION_COMPLETED
        }

        public State withStatus(Status newStatus) {
            return new State(txId, transaction, newStatus, started, ended, duration, history);
        }

        public State complete() {
            var ended = System.currentTimeMillis();
            var duration = ended - started;
            return new State(txId, transaction, status, started, ended, duration, history);
        }

        public static State from(String txId, Payment request) {
            return new State(
                txId,
                new Transaction(request.from(), request.to(), request.amount()),
                VALIDATING_REQUEST,
                System.currentTimeMillis(),
                0L,
                0L,
                new StepStack()
            );
        }

        public State logStep(String stepName, String stepStatus) {
            return new State(
                txId,
                transaction,
                status,
                started,
                ended,
                duration,
                history.push(new StepEntry(stepName, stepStatus, System.currentTimeMillis()))
            );
        }

    }

    private static Long transactionDuration(State state) {
        if(state.ended() == 0 || state.started() == 0) {
            return 0L;
        }
        return state.ended() - state.started();
    }

}
