package org.jetbrains.dokka.gradle

import groovy.lang.Closure
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskAction
import org.jetbrains.dokka.DokkaGenerator
import org.jetbrains.dokka.SourceLinkDefinition
import java.io.File
import java.util.ArrayList

public open class DokkaPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("dokka", DokkaExtension::class.java)
        project.tasks.create("dokka", DokkaTask::class.java)

        ext.moduleName = project.name
        ext.outputDirectory = File(project.buildDir, "dokka").absolutePath
    }
}

public open class DokkaTask : DefaultTask() {
    init {
        group = JavaBasePlugin.DOCUMENTATION_GROUP
        description = "Generates dokka documentation for Kotlin"
    }

    @TaskAction
    fun generate() {
        val project = project
        val conf = project.extensions.getByType(DokkaExtension::class.java)
        val javaPluginConvention = project.convention.getPlugin(JavaPluginConvention::class.java)

        val sourceSets = javaPluginConvention.sourceSets?.findByName(SourceSet.MAIN_SOURCE_SET_NAME)
        val sourceDirectories = sourceSets?.allSource?.srcDirs?.filter { it.exists() } ?: emptyList()
        val allConfigurations = project.configurations

        val classpath =
                conf.processConfigurations
                .map { allConfigurations?.getByName(it) ?: throw IllegalArgumentException("No configuration $it found") }
                .flatMap { it }

        if (sourceDirectories.isEmpty()) {
            logger.warn("No source directories found: skipping dokka generation")
            return
        }

        DokkaGenerator(
                DokkaGradleLogger(logger),
                classpath.map { it.absolutePath },
                sourceDirectories.map { it.absolutePath },
                conf.samples,
                conf.includes,
                conf.moduleName,
                conf.outputDirectory,
                conf.outputFormat,
                conf.linkMappings.map { SourceLinkDefinition(project.file(it.dir).absolutePath, it.url, it.suffix) },
                false
        ).generate()
    }

}

public open class DokkaExtension {
    var moduleName: String = ""
    var outputFormat: String = "html"
    var outputDirectory: String = ""
    var processConfigurations: ArrayList<String> = arrayListOf("compile")
    var includes: ArrayList<String> = arrayListOf()
    var linkMappings: ArrayList<LinkMapping> = arrayListOf()
    var samples: ArrayList<String> = arrayListOf()

    fun linkMapping(closure: Closure<Any?>) {
        val mapping = LinkMapping()
        closure.delegate = mapping
        closure.call()

        if (mapping.dir.isEmpty()) {
            throw IllegalArgumentException("Link mapping should have dir")
        }
        if (mapping.url.isEmpty()) {
            throw IllegalArgumentException("Link mapping should have url")
        }

        linkMappings.add(mapping)
    }
}

public open class LinkMapping {
    var dir: String = ""
    var url: String = ""
    var suffix: String? = null
}
