package com.example.util;

import kalix.javasdk.DeferredCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

public class Validator {

    private static final Logger log = LoggerFactory.getLogger(Validator.class);

    public static ValidationBuilder start(){
        return new ValidationBuilder(List.of());
    }

    public static ValidationBuilder validate(Validation... validations){
        return new ValidationBuilder(validations);
    }

    public record ValidationBuilder(List<Validation> validations, List<ServiceValidation> serviceValidations, List<String> reasons, Mode mode){

        public ValidationBuilder(List<Validation> validations){
            this(validations, List.of(), List.of(), Mode.PASSIVE);
        }

        public ValidationBuilder(Validation[] validations){
            this(Arrays.stream(validations).toList(), List.of(), List.of(), Mode.PASSIVE);
        }

        public ValidationBuilder validate(Validation... validationsIn){
            var current = new ArrayList<>(validations);
            current.addAll(Arrays.stream(validationsIn).toList());
            return new ValidationBuilder(current, serviceValidations, reasons, mode);
        }

        public ValidationBuilder validateFields(String field, Map<String, String> fields, Function<String, Validation> validation){
            if(fields.containsKey(field)) {
                var current = new ArrayList<>(validations);
                current.add(validation.apply(fields.get(field)));
                return new ValidationBuilder(current, serviceValidations, reasons, mode);
            }
            return this;
        }

        public ValidationBuilder mode(Mode mode){
            return new ValidationBuilder(validations, serviceValidations, reasons, mode);
        }

        public ValidationBuilder resolve(ServiceValidation... serviceValidationsIn){
            var current = new ArrayList<>(serviceValidations);
            current.addAll(Arrays.stream(serviceValidationsIn).toList());
            return new ValidationBuilder(validations, current, reasons, mode);
        }

        public ValidationBuilder resolveIfExists(String field, Map<String, String> fields, Function<String, ServiceValidation> serviceValidation){
            if(fields.containsKey(field)) {
                var current = new ArrayList<>(serviceValidations);
                current.add(serviceValidation.apply(fields.get(field)));
                return new ValidationBuilder(validations, current, reasons, mode);
            }
            return this;
        }

        public <T> T handle(BiFunction<Result, String, T> func){

            //Nothing to validate
            if(validations.isEmpty() && serviceValidations.isEmpty())
                return func.apply(Result.SUCCESS, "");

            var results = new ArrayList<String>();

            //Otherwise iterate over validations, depending on evaluation mode
            for(Validation validation : validations){
                if(validation.result()){
                    results.add(validation.message());
                    if(mode == Mode.FAIL_FAST) break;
                }
            }

            if(results.isEmpty() || mode == Mode.PASSIVE)
                for(ServiceValidation serviceValidation : serviceValidations){
                    if(serviceValidation.result()){
                        results.add(serviceValidation.message());
                        if(mode == Mode.FAIL_FAST) break;
                    }
                }

            if(results.isEmpty()) return func.apply(Result.SUCCESS, "");

            return func.apply(
                Result.ERROR,
                results.stream().reduce("", "%s\n%s"::formatted)
            );

        }

    }

    public sealed interface Validation {
        boolean result();

        String message();
    }

    public record StringValidation(Function<String, Boolean> func, String test, String message) implements Validation {
        @Override public boolean result() { return func.apply(test); }
        @Override public String message() { return message; }
    }

    public record BooleanValidation(Function<Boolean, Boolean> func, boolean test, String message) implements Validation {
        @Override public boolean result() { return func.apply(test); }
        @Override public String message() { return message; }
    }

    public record ObjectValidation(Function<Object, Boolean> func, Object test, String message) implements Validation {
        @Override public boolean result() { return func.apply(test); }
        @Override public String message() { return message; }
    }

    public record IntegerValidation(Function<Integer, Boolean> func, Integer test, String message) implements Validation {
        @Override public boolean result() { return func.apply(test); }
        @Override public String message() { return message; }
    }

    public record BiIntegerValidation(BiFunction<Integer, Integer, Boolean> func, Integer i1, Integer i2, String message) implements Validation {
        @Override public boolean result() { return func.apply(i1, i2); }
        @Override public String message() { return message; }
    }

    public record TriLongValidation(TriFunction<Long, Long, Long, Boolean> func, Long p1, Long p2, Long p3, String message) implements Validation {
        @Override public boolean result() { return func.apply(p1, p2, p3); }
        @Override public String message() { return message; }
    }

    public static Validation isEmpty(String test, String reason){
        return new StringValidation(String::isBlank, test, reason);
    }

    public static Validation isNotEmpty(String test, String reason){
        return new StringValidation(t -> !t.isBlank(), test, reason);
    }

    public static Validation isTrue(boolean test, String reason){
        return new BooleanValidation(b -> b, test, reason);
    }

    public static Validation isFalse(boolean test, String reason){
        return new BooleanValidation(b -> !b, test, reason);
    }

    public static Validation isNull(Object test, String reason){
        return new ObjectValidation(Objects::isNull, test, reason);
    }

    public static Validation isNotNull(Object test, String reason){
        return new ObjectValidation(Objects::nonNull, test, reason);
    }

    public static Validation isLtEqZero(int test, String reason){
        return new IntegerValidation(i -> i <= 0, test, reason);
    }

    public static Validation isLtZero(int test, String reason){
        return new IntegerValidation(i -> i < 0, test, reason);
    }

    public static Validation isGtLimit(int test, int limit, String reason){
        return new BiIntegerValidation((i1, i2) -> i1 > i2, test, limit, reason);
    }

    public static Validation isFutureDate(Long dateTestMillis, Long thresholdMillis, Long baselineMillis, String reason){
        return new TriLongValidation(
            (t1, t2, t3) -> t1 + t2 > t3,
            dateTestMillis,  //t1
            thresholdMillis, //t2
            baselineMillis,  //t3
            reason
        );
    }

    public static Validation isPastDate(Long dateTestMillis, Long thresholdMillis, Long baselineMillis, String reason){
        return new TriLongValidation(
            (t1, t2, t3) -> t1 + t2 < t3,
            dateTestMillis,  //t1
            thresholdMillis, //t2
            baselineMillis,  //t3
            reason
        );
    }

    public sealed interface ServiceValidation {
        boolean result();

        String message();
    }

    public record BooleanServiceValidation(DeferredCall<?, ?> call, String reason) implements ServiceValidation {
        @Override public boolean result() {
            try {
                return call.execute().handle((result, ex) -> {
                    return result == null;
                    // I know this can be simplified, but keeping the code block for
                    // various debugging hre and there
                }).toCompletableFuture().join();
            } catch (Exception e) {
                log.error("Service validation failed: %s".formatted(e.getMessage()));
                return false;
            }
        }
        @Override public String message() { return reason; }
    }

    public static ServiceValidation entityExists(DeferredCall<?, ?> call, String reason){
        return new BooleanServiceValidation(call, reason);
    }

    public enum Mode {
        FAIL_FAST,     //Execute validations until first failure
        PASSIVE        //Execute all validations, accumulate results
    }

    public enum Result {
        SUCCESS, ERROR
    }

    @FunctionalInterface
    interface TriFunction<A, B, C, R> {
        R apply(A a, B b, C c);
    }

}
