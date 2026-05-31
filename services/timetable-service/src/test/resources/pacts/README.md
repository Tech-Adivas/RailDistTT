# Pact Files

Place consumer-generated Pact JSON files here for local provider verification.

In CI, pact files are fetched from the Pact Broker. Set `PACT_BROKER_BASE_URL` and
`PACT_BROKER_TOKEN` environment variables and update `TimetableControllerPactProducerTest`
to use `@PactBroker` instead of `@PactFolder`.

See: https://docs.pact.io/provider/junit5
