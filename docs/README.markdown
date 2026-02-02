# AMA Message Architecture

This is (intended to be) a fast and simple event messaging framework,
with support for:

* Events
* Queries (events that expect/generate a response)
* Running multiple instances of the server as well as client services
* SSL/TLS by default
* Very little configuration

## Configuration

Configuration options are identical for both server and client; an
optional list of server hosts, which defaults to auto and will direct
servers to issue a UDP broadcast message that can be picked up by other
server and clients alike (provided they are on the same network). A
value of none will disable this entirely and essentially force a single
server instance. The SSL/TLS capabilities can also be configured, from
the protocol version, to choosing between PEM certificates or JKS, with
a fall-back default of an ephemeral/in-memory certificate (only really
recommended for testing and development).

    messaging:
      hosts:
        - auto
      ssl:
        protocol: TLS
        pem:
          keyPath: /path/to/server-key.pem
          keyPassword: key-password
          certificatePath: /path/to/server-cert.pem
          trustPath: /path/to/ca-cert.pem
        jks:
          keyStorePath: /path/to/server-key-store.jks
          keyStorePassword: key-store-password
          trustStorePath: /path/to/client-trust-store.jks
          trustStorePassword: trust-store-password

Remember, if you intend to run multiple server instances, they will need
a trust certificate/store.

## Project Layout

There are a few submodules within the project that are outlined below.

### Server

This is the event messaging server, it listens for incoming events and
the routes them to all connected clients, which may include other server
instances. Responses from queries are routed back to where the query
originated. It listens on port 2413.

### Client

This is the Spring Boot client library. It is used by client
applications to publish events. Ultimately, this is all you need to
depend on and all you really need to import is MessagingGateway, which
will be auto-wired as necessary.

### Common

Some common code shared between the client and server. This is also
where the annotations exist for identifying your events and their
handlers.

    @Event
    @EventHandler
    @QueryHandler
    @Response

`@Event` for event and `@Query` for query objects that are published,
`@Response` for responses to queries. They are class level and should
be as POJO as possible. `@EventHandler` and `@QueryHandler` are the
handlers, event handlers should be void (any response they do give is
ignored), whereas query handlers should return a response annotated
object.

The annotations are exposed/available when depending upon the client
library.

### Test

A Spring Boot test library. It contains a simple, in-memory, client/
server model that can be used to verify events/queries being published,
as well as testing the handlers by easily allowing messages to be
dispatched as needed. It also supports more of an end-to-end style of
testing too, with the class/method annotation `@MessagnigDispatch`.
By default, the test client will not forward events or queries
preferring a more isolated approach to tests. If `@MessagnigDispatch`
is applied at the class level, `@MessagnigNoDispatch` can disable this
functionality on a per-test basis.

For verification there are some simple matchers, which work similarly
to those found in Mockito and Hamcrest.

    of()       // to match by class
    excatly()  // to match by calling Objects.equals()
    matching() // to match by predicate; allows for more fine-graind comparisons
    anyValue() // to match anything at all
