package de.acetous.dependencycompliance;

import org.gradle.testkit.runner.BuildResult;
import org.junit.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

public class UnresolvableDependencyTest extends AbstractTest {

    @Test
    public void listingFailsWhenDependencyCanNotBeResolved() throws IOException {
        // given
        copyFile("unresolvable/unresolvable.gradle", "build.gradle");

        // when
        BuildResult buildResult = createGradleRunner()
                .withArguments("dependencyComplianceList")
                .buildAndFail();

        // then
        assertThat(buildResult.getOutput()).containsIgnoringWhitespaces("The following dependencies cannot be resolved: [commons-io:commons-io:2.11.0]");
    }

}
