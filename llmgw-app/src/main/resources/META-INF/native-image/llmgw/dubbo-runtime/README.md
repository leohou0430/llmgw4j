# Dubbo 3.3.6 runtime reflection

Captured with the GraalVM 25 native-image agent from local WebFlux and VT
startup, health, JSON and SSE requests. Only `org.apache.dubbo.*` types present
in dubbo-common were retained; optional Log4j adapters with absent logging
libraries were excluded. No Spring/JDK/vendor-generated blanket configuration
was copied. Entries describe constructor calls and constant-field access,
including scoped beans not named in SPI descriptors.

When upgrading Dubbo, repeat agent training and run the actual native binary
contracts. The dynamic app AOT registrar handles classpath SPI implementations;
this file covers Dubbo's internal reflective runtime construction.
