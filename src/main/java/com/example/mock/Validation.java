package com.example.mock;

import com.example.account.Account;
import com.example.util.Validator;
import kalix.javasdk.action.Action;
import kalix.javasdk.client.ComponentClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@RequestMapping("/validation")
public class Validation extends Action {

    /**
     * Validation is a component that checks if a transaction is valid.
     *
     * Checks if the transaction is valid
     * - Check if the accounts are valid
     * - Check if the balances are valid, not liquidity, but within the limits
     *   - ie: minimum payment or transfer amount
     *
     * For the sake of simplicity, the logic here is just performing basic checks.
     * - In a real-world scenario, this would be more complex.
     *
     */

    private static final Logger log = LoggerFactory.getLogger(Validation.class);

    private final ComponentClient client;

    public Validation(ComponentClient client) {
        this.client = client;
    }

    @PostMapping("/validate")
    public Effect<ValidationResult> validate(@RequestBody Validate.Transaction request) {
        log.info("Validating transaction: {}", request);
        return Validator
            .validate(
                Validator.isTrue(request.txId.isEmpty(), "Transaction ID is Required"),
                Validator.isTrue(request.amount <= 0, "Transaction amount must be greater than 0"),
                Validator.isTrue(request.source.isEmpty(), "Source Account is Required"),
                Validator.isTrue(request.destination.isEmpty(), "Destination Account is Required")
            )
            .resolve(
                Validator.entityExists(
                    client.forEventSourcedEntity(request.source).call(Account::get),
                    "Source Account Not Found"
                ),
                Validator.entityExists(
                    client.forEventSourcedEntity(request.destination).call(Account::get),
                    "Destination Account Not Found"
                )
            )
            .handle((result, err) -> switch(result){
                case SUCCESS -> effects().reply(new ValidationResult.Approved());
                case ERROR -> effects().reply(new ValidationResult.Rejected(err));
            });
    }

    public sealed interface Validate  {

        record Transaction(String txId, String source, String destination, int amount) implements Validate {}

    }

    public sealed interface ValidationResult  {

        record Rejected(String reason) implements ValidationResult {}

        record Approved() implements ValidationResult {}

    }

}
