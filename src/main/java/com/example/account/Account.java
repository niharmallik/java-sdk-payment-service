package com.example.account;

import com.example.util.Validator;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.grpc.Status;
import kalix.javasdk.annotations.EventHandler;
import kalix.javasdk.annotations.Id;
import kalix.javasdk.annotations.TypeId;
import kalix.javasdk.annotations.TypeName;
import kalix.javasdk.eventsourcedentity.EventSourcedEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import static com.example.util.Validator.*;

@Id("id")
@TypeId("account-entity")
@RequestMapping("/account/{id}")
public class Account extends EventSourcedEntity<Account.State, Account.Event> {

    private static final Logger log = LoggerFactory.getLogger(Account.class);

    @Override
    public State emptyState() { return State.emptyState(); }

    @PostMapping("/create/{initBalance}")
    public Effect<String> create(@PathVariable String id, @PathVariable int initBalance) {
        return Validator
            .validate(
                isFalse(currentState().isEmpty(), "Account Already Exists")
            )
            .handle((result, err) -> switch(result){
                case SUCCESS -> effects()
                    .emitEvent(new Event.AccountCreated(id, initBalance))
                    .thenReply(__ -> "ok");
                case ERROR -> effects().error(err, Status.Code.ALREADY_EXISTS);
            });
    }

    @PostMapping("/deposit/{amount}")
    public Effect<DepositResult> deposit(@PathVariable int amount) {
        State current = currentState();
        State updated = current.deposit(amount);
        return Validator
            .validate(
                isTrue(current.isEmpty(), "Account [" + commandContext().entityId() + "] Doesn't Exist")
            )
            .handle((result, err) -> switch(result){
                case SUCCESS -> effects()
                    .emitEvent(new Event.FundsDeposited(updated.balance, current.balance))
                    .thenReply(__ -> new DepositResult.DepositSucceed());
                case ERROR -> effects()
                    .reply(new DepositResult.DepositFailed(err));
            });
    }

    @PostMapping("/withdraw/{amount}")
    public Effect<WithdrawResult> withdraw(@PathVariable int amount) {
        State current = currentState();
        State updated = current.withdraw(amount);
        return Validator
            .validate(
                isTrue(current.isEmpty(), "Account [" + commandContext().entityId() + "] Doesn't Exist"),
                isLtZero(updated.balance, "Insufficient funds")
            )
            .mode(Mode.FAIL_FAST)
            .handle((result, err) -> switch(result){
                case SUCCESS -> effects()
                    .emitEvent(new Event.FundsWithdrawn(updated.balance, current.balance))
                    .thenReply(__ -> new WithdrawResult.WithdrawSucceed());
                case ERROR -> effects()
                    .reply(new WithdrawResult.WithdrawFailed(err));
            });
    }

    @GetMapping
    public Effect<Integer> get(){
        if(currentState().isEmpty())
            return effects().error("Not found", Status.Code.NOT_FOUND);
        return effects().reply(currentState().balance);
    }

    @GetMapping("/verify-funds/{amount}")
    public Effect<Boolean> verifyFunds(@PathVariable int amount){
        return effects().reply(currentState().balance >= amount);
    }

    @EventHandler
    public State onAccountCreated(Event.AccountCreated event) {
        return new State(event.id, event.initBalance);
    }

    @EventHandler
    public State onFundsDeposited(Event.FundsDeposited event) {
        return currentState().balance(event.newBalance);
    }

    @EventHandler
    public State onFundsWithdrawn(Event.FundsWithdrawn event) {
        return currentState().balance(event.newBalance);
    }

    public sealed interface Event {

        @TypeName("account-created")
        record AccountCreated(String id, int initBalance) implements Event {}

        @TypeName("funds-deposited")
        record FundsDeposited(int newBalance, int prevBalance) implements Event {}

        @TypeName("funds-withdrawn")
        record FundsWithdrawn(int newBalance, int prevBalance) implements Event {}

    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Account.WithdrawResult.WithdrawSucceed.class, name = "withdraw-succeed"),
        @JsonSubTypes.Type(value = Account.WithdrawResult.WithdrawFailed.class, name = "withdraw-failed")
    })
    public sealed interface WithdrawResult {

        record WithdrawFailed(String errorMsg) implements Account.WithdrawResult {}

        record WithdrawSucceed() implements Account.WithdrawResult {}

    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Account.DepositResult.DepositSucceed.class, name = "deposit-succeed"),
        @JsonSubTypes.Type(value = Account.DepositResult.DepositFailed.class, name = "deposit-failed")
    })
    public sealed interface DepositResult {
        record DepositFailed(String errorMsg) implements Account.DepositResult {
        }

        record DepositSucceed() implements Account.DepositResult {
        }
    }

    public record State(String id, int balance) {

        public State withdraw(int amount) {
            return new State(id, balance - amount);
        }

        public State deposit(int amount) {
            return new State(id, balance + amount);
        }

        public State balance(int amount) {
            return new State(id, amount);
        }

        public static State emptyState() {
            return new State("", 0);
        }

        public boolean isEmpty() {
            return id.isEmpty();
        }

    }

}
