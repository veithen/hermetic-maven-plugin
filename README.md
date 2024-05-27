# hermetic-maven-plugin

This Maven plugin configures the Maven build so that tests are executed hermetically, i.e. without access to remote resources. To achieve this the plugin generates a Java 2 security policy and configures a custom security manager. By default the corresponding command line arguments are added to the `argLine` property so that they are picked up by [maven-surefire-plugin](http://maven.apache.org/surefire/maven-surefire-plugin/test-mojo.html#argLine).

## Making maven-invoker-plugin executions hermetic

By default the plugin only makes unit tests hermetic. To make integration tests for Maven plugins hermetic, add the following line to the maven-invoker-plugin configuration:

    <mavenOpts>${argLine}</mavenOpts>

If the integration test projects have unit tests, their execution will fail because maven-surefire-plugin tries to fork a VM (which by default is forbidden by the generated security policy). Use one of the following options to solve this:

1.   Add `<forkCount>0</forkCount>` to the maven-surefire-plugin configuration in the test projects.

2.   Add `<allowExec>true</allowExec>` to the hermetic-maven-plugin configuration in the main project. You should then add `<argLine>@argLine@</argLine>` to the maven-surefire-plugin configuration in the test projects so that the unit tests in those projects are executed hermetically.
