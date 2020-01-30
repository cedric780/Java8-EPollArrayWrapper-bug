This maven project demonstrate that sun.nio.ch.EPollArrayWrapper bug where EPollArrayWrapper.epollWait(...)
 may return events for file descriptors that were previously removed.

The consequences are that Jetty spins into infinite loops and consumes CPU for nothing.

How to execute:
---------------

1. Extract the jar file in a directory
2. Go to the directory
3. Execute maven package
4. Execute 'java -jar './target/org.modelio.jre.epollarray.test-0.0.1-jar-with-dependencies.jar'
