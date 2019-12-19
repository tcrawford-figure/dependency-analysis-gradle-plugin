@file:Suppress("UnstableApiUsage")

package com.autonomousapps

import com.android.build.gradle.AppExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.internal.publishing.AndroidArtifacts
import com.android.build.gradle.internal.tasks.BundleLibraryClasses
import com.autonomousapps.internal.capitalize
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.UnknownTaskException
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.FileTree
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.the
import org.gradle.kotlin.dsl.withType
import java.util.concurrent.Callable

private const val ANDROID_APP_PLUGIN = "com.android.application"
private const val ANDROID_LIBRARY_PLUGIN = "com.android.library"
private const val JAVA_LIBRARY_PLUGIN = "java-library"

@Suppress("unused")
class DependencyAnalysisPlugin : Plugin<Project> {

    override fun apply(project: Project): Unit = project.run {
        pluginManager.withPlugin(ANDROID_APP_PLUGIN) {
            logger.debug("Adding Android tasks to ${project.path}")
            analyzeAndroidApplicationDependencies()
        }
        pluginManager.withPlugin(ANDROID_LIBRARY_PLUGIN) {
            logger.debug("Adding Android tasks to ${project.path}")
            analyzeAndroidLibraryDependencies()
        }
        pluginManager.withPlugin(JAVA_LIBRARY_PLUGIN) {
            logger.debug("Adding JVM tasks to ${project.path}")
            analyzeJavaLibraryDependencies()
        }

        if (this == rootProject) {
            logger.debug("Adding root project tasks")
            addAggregatingTasks()
        }
    }

    // TODO cleanup. Variant-aware? Maybe via extension?
    private fun Project.addAggregatingTasks() {
        val variant = "debug"
        val dep = Callable {
            subprojects.mapNotNull { proj ->
                proj.tasks.withType<DependencyMisuseTask>().matching {
                    it.name.contains(variant, ignoreCase = true) ||
                        it.name.contains("debug", ignoreCase = true) ||
                        it.name.contains("main", ignoreCase = true)
                }.firstOrNull()
            }
        }
        tasks.register<DependencyMisuseAggregateReportTask>("misusedDependenciesReport") {
            dependsOn(dep)

            projectReportCallables = dep
            projectReport.set(project.layout.buildDirectory.file("$ROOT_DIR/misused-dependencies.txt"))
            projectReportPretty.set(project.layout.buildDirectory.file("$ROOT_DIR/misused-dependencies-pretty.txt"))
        }

        val abi = Callable {
            subprojects.mapNotNull { proj ->
                proj.tasks.withType<AbiAnalysisTask>().matching {
                    it.name.contains(variant, ignoreCase = true) ||
                        it.name.contains("debug", ignoreCase = true) ||
                        it.name.contains("main", ignoreCase = true)
                }.firstOrNull()
            }
        }
        tasks.register<AbiAnalysisAggregateReportTask>("abiReport") {
            dependsOn(abi)

            projectReportCallables = abi
            projectReport.set(project.layout.buildDirectory.file("$ROOT_DIR/abi.txt"))
            projectReportPretty.set(project.layout.buildDirectory.file("$ROOT_DIR/abi-pretty.txt"))
        }
    }

    private fun Project.analyzeAndroidApplicationDependencies() {
        // We need the afterEvaluate so we can get a reference to the `KotlinCompile` tasks. This is due to use of the
        // pluginManager.withPlugin API. Currently configuring the com.android.application plugin, not any Kotlin
        // plugin. I do not know how to wait for both plugins to be ready.
        afterEvaluate {
            the<AppExtension>().applicationVariants.all {
                val androidClassAnalyzer = AndroidAppAnalyzer(this@analyzeAndroidApplicationDependencies, this)
                analyzeDependencies(androidClassAnalyzer)
            }
        }
    }

    private fun Project.analyzeAndroidLibraryDependencies() {
        the<LibraryExtension>().libraryVariants.all {
            val androidClassAnalyzer = AndroidLibAnalyzer(this@analyzeAndroidLibraryDependencies, this)
            analyzeDependencies(androidClassAnalyzer)
        }
    }

    private fun Project.analyzeJavaLibraryDependencies() {
        the<JavaPluginConvention>().sourceSets
            .filterNot { it.name == "test" }
            .forEach { sourceSet ->
                try {
                    val javaModuleClassAnalyzer = JavaLibAnalyzer(this, sourceSet)
                    analyzeDependencies(javaModuleClassAnalyzer)
                } catch (e: UnknownTaskException) {
                    logger.warn("Skipping tasks creation for sourceSet `${sourceSet.name}`")
                }
            }
    }

    private fun <T : ClassAnalysisTask> Project.analyzeDependencies(dependencyAnalyzer: DependencyAnalyzer<T>) {
        val variantName = dependencyAnalyzer.variantName
        val variantTaskName = dependencyAnalyzer.variantNameCapitalized

        val analyzeClassesTask = dependencyAnalyzer.registerClassAnalysisTask()

        // 2.
        // Produces a report that lists all direct and transitive dependencies, their artifacts, and component type
        // (library vs project)
        val artifactsReportTask = tasks.register<ArtifactsAnalysisTask>("artifactsReport$variantTaskName") {
            val artifactCollection =
                configurations[dependencyAnalyzer.compileConfigurationName].incoming.artifactView {
                    attributes.attribute(dependencyAnalyzer.attribute, dependencyAnalyzer.attributeValue)
                }.artifacts

            artifactFiles = artifactCollection.artifactFiles
            artifacts = artifactCollection

            output.set(layout.buildDirectory.file(getArtifactsPath(variantName)))
            outputPretty.set(layout.buildDirectory.file(getArtifactsPrettyPath(variantName)))
        }

        val dependencyReportTask =
            tasks.register<DependencyReportTask>("dependenciesReport$variantTaskName") {
                artifactFiles =
                    configurations.getByName(dependencyAnalyzer.runtimeConfigurationName).incoming.artifactView {
                        attributes.attribute(dependencyAnalyzer.attribute, dependencyAnalyzer.attributeValue)
                    }.artifacts.artifactFiles
                configurationName.set(dependencyAnalyzer.runtimeConfigurationName)
                allArtifacts.set(artifactsReportTask.flatMap { it.output })

                output.set(layout.buildDirectory.file(getAllDeclaredDepsPath(variantName)))
                outputPretty.set(layout.buildDirectory.file(getAllDeclaredDepsPrettyPath(variantName)))
            }

        tasks.register<DependencyMisuseTask>("misusedDependencies$variantTaskName") {
            artifactFiles =
                configurations.getByName(dependencyAnalyzer.runtimeConfigurationName).incoming.artifactView {
                    attributes.attribute(dependencyAnalyzer.attribute, dependencyAnalyzer.attributeValue)
                }.artifacts.artifactFiles
            configurationName.set(dependencyAnalyzer.runtimeConfigurationName)
            declaredDependencies.set(dependencyReportTask.flatMap { it.output })
            usedClasses.set(analyzeClassesTask.flatMap { it.output })

            outputUnusedDependencies.set(
                layout.buildDirectory.file(getUnusedDirectDependenciesPath(variantName))
            )
            outputUsedTransitives.set(
                layout.buildDirectory.file(getUsedTransitiveDependenciesPath(variantName))
            )
            outputHtml.set(
                layout.buildDirectory.file(getMisusedDependenciesHtmlPath(variantName))
            )
        }

        dependencyAnalyzer.registerAbiAnalysisTask(dependencyReportTask)
    }

    private interface DependencyAnalyzer<T : ClassAnalysisTask> {
        /**
         * E.g., `flavorDebug`
         */
        val variantName: String
        /**
         * E.g., `FlavorDebug`
         */
        val variantNameCapitalized: String
        val compileConfigurationName: String
        val runtimeConfigurationName: String
        val attribute: Attribute<String>
        val attributeValue: String

        // 1.
        // This produces a report that lists all of the used classes (FQCN) in the project
        fun registerClassAnalysisTask(): TaskProvider<out T>

        // This is a no-op for com.android.application projects, since they have no meaningful ABI
        fun registerAbiAnalysisTask(dependencyReportTask: TaskProvider<DependencyReportTask>) = Unit
    }

    /**
     * Base class for analyzing an Android project (com.android.application or com.android.library only).
     */
    private abstract class AndroidAnalyzer<T : ClassAnalysisTask>(
        protected val project: Project,
        protected val variant: BaseVariant
    ) : DependencyAnalyzer<T> {

        final override val variantName: String = variant.name
        final override val variantNameCapitalized: String = variantName.capitalize()
        final override val compileConfigurationName = "${variantName}CompileClasspath"
        final override val runtimeConfigurationName = "${variantName}RuntimeClasspath"
        final override val attribute: Attribute<String> = AndroidArtifacts.ARTIFACT_TYPE
        final override val attributeValue = "android-classes"

        // Best guess as to path to kapt-generated Java stubs
        protected fun getKaptStubs(): FileTree = project.layout.buildDirectory.asFileTree.matching {
            include("**/kapt*/**/${variantName}/**/*.java")
        }
    }

    private class AndroidLibAnalyzer(
        project: Project, variant: BaseVariant
    ) : AndroidAnalyzer<JarAnalysisTask>(project, variant) {

        // Known to exist in AGP 3.5 and 3.6
        private fun getBundleTask() =
            project.tasks.named("bundleLibCompile$variantNameCapitalized", BundleLibraryClasses::class.java)

        override fun registerClassAnalysisTask(): TaskProvider<JarAnalysisTask> {
            // Known to exist in AGP 3.5 and 3.6
            val bundleTask =
                project.tasks.named("bundleLibCompile$variantNameCapitalized", BundleLibraryClasses::class.java)

            return project.tasks.register<JarAnalysisTask>("analyzeClassUsage$variantNameCapitalized") {
                jar.set(bundleTask.flatMap { it.output })
                kaptJavaStubs.from(getKaptStubs())
                layouts(variant.sourceSets.flatMap { it.resDirectories })

                output.set(project.layout.buildDirectory.file(getAllUsedClassesPath(variantName)))
            }
        }

        override fun registerAbiAnalysisTask(dependencyReportTask: TaskProvider<DependencyReportTask>) {
            project.tasks.register<AbiAnalysisTask>("abiAnalysis$variantNameCapitalized") {
                jar.set(getBundleTask().flatMap { it.output })
                dependencies.set(dependencyReportTask.flatMap { it.output })

                output.set(project.layout.buildDirectory.file(getAbiAnalysisPath(variantName)))
                abiDump.set(project.layout.buildDirectory.file(getAbiDumpPath(variantName)))
            }
        }
    }

    private class AndroidAppAnalyzer(
        project: Project, variant: BaseVariant
    ) : AndroidAnalyzer<ClassListAnalysisTask>(project, variant) {

        override fun registerClassAnalysisTask(): TaskProvider<ClassListAnalysisTask> {
            // Known to exist in Kotlin 1.3.50.
            val kotlinCompileTask = project.tasks.named("compile${variantNameCapitalized}Kotlin") // KotlinCompile
            // Known to exist in AGP 3.5 and 3.6, albeit with different backing classes (AndroidJavaCompile and JavaCompile)
            val javaCompileTask = project.tasks.named("compile${variantNameCapitalized}JavaWithJavac")

            return project.tasks.register<ClassListAnalysisTask>("analyzeClassUsage$variantNameCapitalized") {
                kotlinClasses.from(kotlinCompileTask.get().outputs.files.asFileTree)
                javaClasses.from(javaCompileTask.get().outputs.files.asFileTree)
                kaptJavaStubs.from(getKaptStubs()) // TODO some issue here with cacheability... (need build comparisons)
                layouts(variant.sourceSets.flatMap { it.resDirectories })

                output.set(project.layout.buildDirectory.file(getAllUsedClassesPath(variantName)))
            }
        }
    }

    private class JavaLibAnalyzer(
        private val project: Project,
        private val sourceSet: SourceSet
    ) : DependencyAnalyzer<JarAnalysisTask> {

        override val variantName: String = sourceSet.name
        override val variantNameCapitalized = variantName.capitalize()
        // Yes, these two are the same for this case
        override val compileConfigurationName = "compileClasspath"
        override val runtimeConfigurationName = compileConfigurationName
        override val attribute: Attribute<String> = Attribute.of("artifactType", String::class.java)
        override val attributeValue = "jar"

        private fun getJarTask() = project.tasks.named(sourceSet.jarTaskName, Jar::class.java)

        override fun registerClassAnalysisTask(): TaskProvider<JarAnalysisTask> {
            // Best guess as to path to kapt-generated Java stubs // TODO this is duplicated.
            val kaptStubs = project.layout.buildDirectory.asFileTree.matching {
                include("**/kapt*/**/${variantName}/**/*.java")
            }

            return project.tasks.register<JarAnalysisTask>("analyzeClassUsage$variantNameCapitalized") {
                jar.set(getJarTask().flatMap { it.archiveFile })
                kaptJavaStubs.from(kaptStubs)
                output.set(project.layout.buildDirectory.file(getAllUsedClassesPath(variantName)))
            }
        }

        override fun registerAbiAnalysisTask(dependencyReportTask: TaskProvider<DependencyReportTask>) {
            project.tasks.register<AbiAnalysisTask>("abiAnalysis$variantNameCapitalized") {
                jar.set(getJarTask().flatMap { it.archiveFile })
                dependencies.set(dependencyReportTask.flatMap { it.output })

                output.set(project.layout.buildDirectory.file(getAbiAnalysisPath(variantName)))
                abiDump.set(project.layout.buildDirectory.file(getAbiDumpPath(variantName)))
            }
        }
    }
}

private const val ROOT_DIR = "dependency-analysis"

private fun getVariantDirectory(variantName: String) = "$ROOT_DIR/$variantName"

private fun getArtifactsPath(variantName: String) = "${getVariantDirectory(variantName)}/artifacts.json"

private fun getArtifactsPrettyPath(variantName: String) = "${getVariantDirectory(variantName)}/artifacts-pretty.json"

private fun getAllUsedClassesPath(variantName: String) = "${getVariantDirectory(variantName)}/all-used-classes.txt"

private fun getAllDeclaredDepsPath(variantName: String) =
    "${getVariantDirectory(variantName)}/all-declared-dependencies.json"

private fun getAllDeclaredDepsPrettyPath(variantName: String) =
    "${getVariantDirectory(variantName)}/all-declared-dependencies-pretty.json"

private fun getUnusedDirectDependenciesPath(variantName: String) =
    "${getVariantDirectory(variantName)}/unused-direct-dependencies.json"

private fun getUsedTransitiveDependenciesPath(variantName: String) =
    "${getVariantDirectory(variantName)}/used-transitive-dependencies.json"

private fun getMisusedDependenciesHtmlPath(variantName: String) =
    "${getVariantDirectory(variantName)}/misused-dependencies.html"

private fun getAbiAnalysisPath(variantName: String) = "${getVariantDirectory(variantName)}/abi.txt"

private fun getAbiDumpPath(variantName: String) = "${getVariantDirectory(variantName)}/abi-dump.txt"

// TODO all-used-classes.txt has some weird items in it:
// ANDSCAP -- the regex is grabbing LANDSCAPE and cutting off first and last char
// AST_MOV -- the regex is grabbing LAST_MOVE and cutting off first and last char
// ApiHelper.callSafely and similar -- no idea
// this.achievementsInterceptorProvider -- probably same as above
// DrFever.gif -- what?
// app.tettra.co -- this is only present as a comment in source. So....
// I think two classes of issues:
// 1. Bad regex capturing stuff like LANDSCAPE and adding it as ANDSCAP
// 2. Source-code processing grabbing non-class names because they have a `.` char in them.
// For 2, see JarAnalysisTask and ClassListAnalysisTask. There is a to-do there.