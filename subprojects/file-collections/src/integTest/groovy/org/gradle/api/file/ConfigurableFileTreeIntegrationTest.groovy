/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.file

import org.gradle.integtests.fixtures.AbstractIntegrationSpec

class ConfigurableFileTreeIntegrationTest extends AbstractIntegrationSpec {
    def "can include the elements of a tree using a Groovy closure spec"() {
        buildFile << """
            class SomeTask extends DefaultTask {
                @InputFiles
                final ConfigurableFileTree sourceFiles = project.objects.fileTree()

                @OutputFile
                final RegularFileProperty outputFile = project.objects.fileProperty()

                @TaskAction
                def go() {
                    def names = sourceFiles.files.name.sort().join(",")
                    outputFile.asFile.get().text = names
                }
            }

            task generate(type: SomeTask) {
                sourceFiles.from("src")
                sourceFiles.include { element ->
                    println("checking " + element.relativePath)
                    element.directory || element.relativePath.segments.length == 2
                }
                outputFile = file("out.txt")
            }
        """

        when:
        file("src/ignore.txt").createFile()
        file("src/a/a.txt").createFile()
        file("src/a/b/ignore.txt").createFile()
        file("src/c/c.txt").createFile()

        run("generate")

        then:
        file("out.txt").text == "a.txt,c.txt"

        when:
        file("src/ignore-2.txt").createFile()

        run("generate")

        then:
        result.assertTaskSkipped(":generate")
        output.count("checking") == 8
        outputContains("checking a/a.txt")
        outputContains("checking ignore-2.txt")
        file("out.txt").text == "a.txt,c.txt"

        when:
        file("src/d/d.txt").createFile()
        file("src/ignore-3.txt").createFile()

        run("generate")

        then:
        result.assertTaskNotSkipped(":generate")
        output.count("checking") == 22 // checked twice, once to snapshot and once when the task action runs. Should be memoized when snapshotting
        outputContains("checking a/a.txt")
        outputContains("checking d/d.txt")
        file("out.txt").text == "a.txt,c.txt,d.txt"
    }

    def "can exclude the elements of a tree using a Groovy closure spec"() {
        buildFile << """
            class SomeTask extends DefaultTask {
                @InputFiles
                final ConfigurableFileTree sourceFiles = project.objects.fileTree()

                @OutputFile
                final RegularFileProperty outputFile = project.objects.fileProperty()

                @TaskAction
                def go() {
                    def names = sourceFiles.files.name.sort().join(",")
                    outputFile.asFile.get().text = names
                }
            }

            task generate(type: SomeTask) {
                sourceFiles.from("src")
                sourceFiles.exclude { element ->
                    println("checking " + element.relativePath)
                    !element.directory && element.relativePath.segments.length == 2
                }
                outputFile = file("out.txt")
            }
        """

        when:
        file("src/a.txt").createFile()
        file("src/a/ignore.txt").createFile()
        file("src/a/b/c.txt").createFile()

        run("generate")

        then:
        file("out.txt").text == "a.txt,c.txt"

        when:
        file("src/a/ignore-2.txt").createFile()

        run("generate")

        then:
        result.assertTaskSkipped(":generate")
        output.count("checking") == 6
        outputContains("checking a.txt")
        outputContains("checking a/ignore-2.txt")
        file("out.txt").text == "a.txt,c.txt"

        when:
        file("src/d/e/f.txt").createFile()
        file("src/a/ignore-3.txt").createFile()

        run("generate")

        then:
        result.assertTaskNotSkipped(":generate")
        output.count("checking") == 20 // checked twice, once for snapshots and once when the task action runs. Should be memoized when snapshotting
        outputContains("checking a.txt")
        outputContains("checking d/e/f.txt")
        file("out.txt").text == "a.txt,c.txt,f.txt"
    }

    def "can filter the elements of a tree using a closure that receives pattern set"() {
        given:
        file('files/one.txt').createFile()
        file('files/a/one.txt').createFile()
        file('files/b/ignore.txt').createFile()
        file('files/b/one.ignore').createFile()
        buildFile << """
            def files = fileTree(dir: 'files')
            files.include('**/*one*')
            def filtered = files.matching {
                include('**/*.txt')
                exclude('**/*ignore*')
            }
            task copy(type: Copy) {
                from filtered
                into 'dest'
            }
        """

        when:
        run 'copy'

        then:
        file('dest').assertHasDescendants(
            'one.txt',
            'a/one.txt'
        )

        when:
        file('files/a/more-ignore.txt').createFile() // not an input
        run 'copy'

        then:
        result.assertTaskSkipped(':copy')
        file('dest').assertHasDescendants(
            'one.txt',
            'a/one.txt'
        )

        when:
        file('files/c/more-one.txt').createFile()
        run 'copy'

        then:
        result.assertTaskNotSkipped(':copy')
        file('dest').assertHasDescendants(
            'one.txt',
            'a/one.txt',
            'c/more-one.txt'
        )
    }
}
