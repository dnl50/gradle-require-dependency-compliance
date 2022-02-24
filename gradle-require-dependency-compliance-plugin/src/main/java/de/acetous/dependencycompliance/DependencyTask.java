package de.acetous.dependencycompliance;

import de.acetous.dependencycompliance.export.DependencyFilterService;
import de.acetous.dependencycompliance.export.DependencyIdentifier;
import de.acetous.dependencycompliance.export.RepositoryIdentifier;
import org.gradle.api.DefaultTask;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.artifacts.result.UnresolvedDependencyResult;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.initialization.dsl.ScriptHandler;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Parent-Class for all plugin tasks.
 */
public abstract class DependencyTask extends DefaultTask {

    protected static final Charset CHARSET = StandardCharsets.UTF_8;

    protected final RegularFileProperty outputFile = getProject().getObjects().fileProperty();

    private final ListProperty<String> ignore = getProject().getObjects().listProperty(String.class);

    private final Property<Boolean> ignoreMavenLocal = getProject().getObjects().property(Boolean.class);

    private final DependencyFilterService dependencyFilterService = new DependencyFilterService();

    /**
     * Set if the local Maven repository should be ignored.
     *
     * @return the property.
     */
    @Input
    public Property<Boolean> getIgnoreMavenLocal() {
        return ignoreMavenLocal;
    }

    /**
     * Set the {@code outputFile}.
     *
     * @return The file.
     */
    @OutputFile
    public RegularFileProperty getOutputFile() {
        return this.outputFile;
    }

    /**
     * Set filtered dependencies.
     *
     * @return The list of dependencies.
     */
    @Input
    public ListProperty<String> getIgnore() {
        return this.ignore;
    }

    protected Set<DependencyIdentifier> loadDependencyFilter() {
        return dependencyFilterService.getDependencyFilter(ignore.get());
    }

    private boolean filterIgnoredDependencies(DependencyIdentifier dependencyIdentifier) {
        return !dependencyFilterService.isIgnored(dependencyIdentifier, loadDependencyFilter());
    }

    /**
     * Resolve all dependencies of all configurations of this project and it's subprojects.
     *
     * @return All resolved dependencies.
     * @see #resolveDependencies(Configuration)
     */
    protected List<DependencyIdentifier> resolveDependencies() {
        return getProject().getAllprojects().stream()
                .flatMap(project -> project.getConfigurations().stream())
                .filter(Configuration::isCanBeResolved)
                .flatMap(resolvableConfiguration -> resolveDependencies(resolvableConfiguration).stream())
                .distinct()
                .sorted(new DependencyIdentifierComparator())
                .filter(this::filterIgnoredDependencies)
                .collect(Collectors.toList());
    }

    /**
     * Resolve all buildscript dependencies of this project and it's subprojects.
     *
     * @return All resolved buildscript dependencies.
     * @see #resolveDependencies(Configuration)
     */
    protected List<DependencyIdentifier> resolveBuildDependencies() {
        return getProject().getAllprojects().stream() //
                .map(project -> project.getBuildscript().getConfigurations().getByName(ScriptHandler.CLASSPATH_CONFIGURATION)) //
                .flatMap(classpathConfiguration -> resolveDependencies(classpathConfiguration).stream())
                .distinct()
                .sorted(new DependencyIdentifierComparator())
                .filter(this::filterIgnoredDependencies)
                .collect(Collectors.toList());
    }

    /**
     * @param configuration The configuration to get the dependencies of. The configuration must be {@link Configuration#isCanBeResolved() resolvable}.
     * @return All module dependency identifiers of the given configuration. {@link #filterIgnoredDependencies(DependencyIdentifier) Ignored} dependencies included for performance reasons.
     * @throws IllegalStateException If one or more dependencies can not be resolved.
     */
    protected Set<DependencyIdentifier> resolveDependencies(Configuration configuration) {
        Set<? extends DependencyResult> allDependencies = configuration.getIncoming()
                .getResolutionResult()
                .getAllDependencies();

        if (containsUnresolvedDependencies(allDependencies)) {
            String unresolvableDependencyDisplayNames = findUnresolvedDependencies(allDependencies).stream()
                    .map(unresolvedDependency -> unresolvedDependency.getAttempted().getDisplayName())
                    .collect(Collectors.joining(", "));

            throw new IllegalStateException(String.format("The following dependencies cannot be resolved: [%s]", unresolvableDependencyDisplayNames));
        }

        return allDependencies.stream()
                .map(ResolvedDependencyResult.class::cast)
                .map(ResolvedDependencyResult::getSelected)
                .map(ResolvedComponentResult::getId)
                .filter(ModuleComponentIdentifier.class::isInstance)
                .map(ModuleComponentIdentifier.class::cast)
                .map(DependencyIdentifier::new)
                .collect(Collectors.toSet());
    }

    /**
     * @param allDependencies The dependencies of a given configuration, not {@code null}.
     * @return {@code true}, iff one or more dependencies in the specified collection can not be resolved.
     */
    private boolean containsUnresolvedDependencies(Collection<? extends DependencyResult> allDependencies) {
        return allDependencies.stream()
                .anyMatch(UnresolvedDependencyResult.class::isInstance);
    }

    /**
     * @param allDependencies The dependencies of a given configuration, not {@code null}.
     * @return All unresolved dependencies.
     */
    private Set<UnresolvedDependencyResult> findUnresolvedDependencies(Collection<? extends DependencyResult> allDependencies) {
        return allDependencies.stream()
                .filter(UnresolvedDependencyResult.class::isInstance)
                .map(UnresolvedDependencyResult.class::cast)
                .collect(Collectors.toSet());
    }

    /**
     * Get all repositories of this project and it's subprojects.
     *
     * @return The repositories.
     */
    protected Set<RepositoryIdentifier> resolveRepositories() {
        return getProject().getAllprojects().stream() //
                .flatMap(project -> project.getRepositories().stream()) //
                .map(RepositoryIdentifier::new) //
                .filter(this::filterMavenLocal) //
                .collect(Collectors.toSet());
    }

    /**
     * Get all buildscript's repositories of this project and it's subprojects.
     *
     * @return The buildscript's repositories.
     */
    protected Set<RepositoryIdentifier> resolveBuildRepositories() {
        return getProject().getAllprojects().stream() //
                .flatMap(project -> project.getBuildscript().getRepositories().stream()) //
                .map(RepositoryIdentifier::new) //
                .filter(this::filterMavenLocal) //
                .collect(Collectors.toSet());
    }

    private boolean filterMavenLocal(RepositoryIdentifier repositoryIdentifier) {
        return !ignoreMavenLocal.get() || !"MavenLocal".equals(repositoryIdentifier.getName());
    }

    public void logDependencyFilter(Set<DependencyIdentifier> dependencyIdentifierList) {
        getLogger().lifecycle("(DependencyCompliance) Ignoring these dependencies:");
        dependencyIdentifierList.forEach(dependencyIdentifier -> getLogger().lifecycle(dependencyIdentifier.toString()));
    }
}
