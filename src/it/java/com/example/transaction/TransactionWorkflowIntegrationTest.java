package com.example.transaction;

import com.example.Main;
import com.example.transaction.TransactionWorkflow.State;
import com.example.account.Account;
import com.google.protobuf.any.Any;
import kalix.javasdk.DeferredCall;
import kalix.spring.testkit.KalixIntegrationTestKitSupport;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.time.temporal.ChronoUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static com.example.transaction.TransactionWorkflow.State.Status.*;

@SpringBootTest(classes = Main.class)
public class TransactionWorkflowIntegrationTest extends KalixIntegrationTestKitSupport {

    private Duration timeout = Duration.of(10, SECONDS);

    @Test
    public void shouldTransferMoney() {
        var accountId1 = randomId();
        var accountId2 = randomId();

        createAccount(accountId1, 100);
        createAccount(accountId2, 100);

        var txId = randomId();
        var payment = new TransactionWorkflow.Payment(
            accountId1,
            accountId2,
            "1",
            10
        );

        Transaction.Response response = execute(componentClient
            .forWorkflow(txId)
            .call(TransactionWorkflow::process)
            .params(accountId1, accountId2, 10)); //temp work around for the issue, should be Payment object

        assertThat(response).asInstanceOf(InstanceOfAssertFactories.type(Transaction.Response.Received.class))
            .extracting(Transaction.Response.Received::status)
            .isEqualTo("VALIDATING_REQUEST");

        await()
            .atMost(10, TimeUnit.of(SECONDS))
            .untilAsserted(() -> {
                var balance1 = getAccountBalance(accountId1);
                var balance2 = getAccountBalance(accountId2);

                assertThat(balance1).isEqualTo(90);
                assertThat(balance2).isEqualTo(110);
            });
    }

    @Test
    public void shouldFailValidationCheck_MissingAccounts() {
        var accountId1 = randomId();
        var accountId2 = randomId();

        //Note: no accounts created here, on purpose...

        var txId = randomId();
        var payment = new TransactionWorkflow.Payment(
            accountId1,
            accountId2,
            "1",
            10
        );//both not exists

        Transaction.Response response = execute(componentClient
            .forWorkflow(txId)
            .call(TransactionWorkflow::process)
            .params(accountId1, accountId2, 10)); //temp workaround for the issue, should be Payment object

        assertThat(response).asInstanceOf(InstanceOfAssertFactories.type(Transaction.Response.Received.class))
            .extracting(Transaction.Response.Received::status)
            .isEqualTo("VALIDATING_REQUEST");

        await()
            .atMost(10, TimeUnit.of(SECONDS))
            .ignoreExceptions()
            .untilAsserted(() -> {
                State state = getTransaction(txId);
                assertThat(state.status()).isEqualTo(VALIDATION_FAILED);
            });
    }


    private String randomId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void createAccount(String accountId, int amount) {
        String response = execute(componentClient
            .forEventSourcedEntity(accountId)
            .call(Account::create)
            .params(accountId, amount));

        assertThat(response).contains("ok");
    }

    private int getAccountBalance(String accountId) {
        return execute(componentClient
            .forEventSourcedEntity(accountId)
            .call(Account::get));
    }

    private State getTransaction(String txId) {
        return execute(componentClient
            .forWorkflow(txId)
            .call(TransactionWorkflow::getTransaction));
    }

    private <T> T execute(DeferredCall<Any, T> deferredCall) {
        try {
            return deferredCall.execute().toCompletableFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException(e);
        }
    }
}