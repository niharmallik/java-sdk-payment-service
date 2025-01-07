# Payment Service Demo showcasing account transfer with workflow compensation

A simple workflow example of funds transfer between two accounts.

Used for code snippets in the Workflow documentation.

## Designing

To understand the Kalix concepts that are the basis for this example, see [Designing services](https://docs.kalix.io/java/development-process.html) in the documentation.

## Developing

This project demonstrates the use of Value Entity and View components.
To understand more about these components, see [Developing services](https://docs.kalix.io/developing-services/)
and in particular the [Java section](https://docs.kalix.io/java/)

## Building

Use Maven to build your project:

```shell
mvn compile
```

## Running Locally

When running a Kalix service locally, we need to have its companion Kalix Runtime running alongside it.

To start your service locally, run:

```shell
mvn kalix:runAll
```

This command will start your Kalix service and a companion Kalix Runtime as configured in [docker-compose.yml](./docker-compose.yml) file.

### Exercising the transfer

With both the Kalix Runtime and your service running, once you have defined endpoints they should be available at `http://localhost:9000`.

Create account `a` with an initial balance

```shell
curl -X POST http://localhost:9000/account/a/create/100
```

Create account `b` with an initial balance

```shell
curl -X POST http://localhost:9000/account/b/create/100
```

Get account `a` current balance

```shell
curl http://localhost:9000/account/a
```

Get account `b` current balance

```shell
curl http://localhost:9000/account/b
```

Start transfer transaction from account `a` to account `b`

```shell
curl http://localhost:9000/transaction/1 \
  -X PUT \
  --header "Content-Type: application/json" \
  --data '{"from": "a", "to": "b", "amount": 10}'
```

Get transfer state

```shell
curl http://localhost:9000/transaction/1
```

## Running integration tests

The integration tests in `src/it` are added by setting `it` as test source directory.
To run the Integration Tests in `src/it/java` use

```shell
mvn verify -Pit
```

## Deploying

To deploy your service, install the `kalix` CLI as documented in
[Install Kalix](https://docs.kalix.io/kalix/install-kalix.html)
and configure a Docker Registry to upload your docker image to.

You will need to update the `dockerImage` property in the `pom.xml` and refer to
[Configuring registries](https://docs.kalix.io/operations/container-registries.html)
for more information on how to make your docker image available to Kalix.

Finally, you can use the [Kalix Console](https://console.kalix.io)
to create a project and then deploy your service into the project either by using `mvn deploy kalix:deploy` which
will conveniently package, publish your docker image, and deploy your service to Kalix, or by first packaging and
publishing the docker image through `mvn deploy` and then deploying the image
through the `kalix` CLI.
