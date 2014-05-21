/*
 * Copyright 2010-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@Grab('net.htmlparser.jericho:jericho-html:3.3')
import net.htmlparser.jericho.Source

import groovyx.gpars.GParsPool
import java.util.concurrent.LinkedBlockingQueue

import static groovyx.gpars.actor.Actors.*

// -----------------------------------
// Handle arguments
// -----------------------------------
def command = System.properties.thisCommand ?: 'groovy mvnsearch.groovy' // for wrapper script
def cli = new CliBuilder(usage:"$command [OPTIONS] KEYWORD..[KEYWORD]")
cli.formatter.width = 80
cli.with {
    h longOpt:'help', 'print this help message'
    g longOpt:'format-grape', 'print Groovy Grape format'
    p longOpt:'format-pom', 'print Maven2 pom format'
    v longOpt:'with-version','with retrieved versions (HEAVY)'
    u longOpt:'with-url', 'with URL of the artifact in mvnsearch.com'
}
cli.metaClass.die = { message ->
    cli.writer.println 'ERROR: ' + message
    cli.usage()
    System.exit 1
}

def opt = cli.parse(args)
if (!opt) System.exit 1
if (opt.h) {
    cli.usage()
    return
}
if (opt.g && opt.p) cli.die 'options --format-xxxxx cannot be specified at the same time'
def keywords = opt.arguments()
if (keywords.size() < 1) cli.die 'KEYWORD must be specified'

// ---------------------
// Prepare closures
// ---------------------
def printArtifact = {
    def isOneline = { option ->
        return !(option.p || option.g || option.v || option.u)
    }
    def doPrint = { artifact, mainPart=null ->
        if (!isOneline(opt)) {
            println "-"*60
            println ">> ${artifact.name}"
        }
        if (mainPart) println mainPart
        if (opt.v) println "versions: " + artifact.versions
        if (opt.u) println artifact.url
    }

    if (opt.p) return { artifact ->
        def writer = new StringWriter()
        new groovy.xml.MarkupBuilder(writer).dependency {
            groupId(artifact.groupId)
            artifactId(artifact.artifactId)
            if (opt.v) version(artifact.latestVersion)
        }
        doPrint artifact, writer
    }
    if (opt.g) return { artifact ->
        def version = (opt.v) ? artifact.latestVersion : '*'
        doPrint artifact, """@Grab("${artifact.groupId}:${artifact.artifactId}:${version}")"""
    }
    return { artifact ->
        doPrint artifact, "${artifact.name} - ${artifact.groupId}:${artifact.artifactId}"
    }
}.call()

def retrieveArtifacts = {
    def artifacts = []
    def queryUrl = new URL("http://mvnrepository.com/search.html?query=${keywords.join('+')}")
    new Source(queryUrl).getAllElements("a")*.getAllElements("class", ~/result-title/).findAll { it }.flatten().each { element ->
        def (all, groupId, artifactId) = (element.attributes.href.value =~ '^/artifact/([^/]+)/([^/]+)$')[0]
        artifacts << [
            name: element.content,
            groupId: groupId,
            artifactId: artifactId,
            url: new URL("http://mvnrepository.com/artifact/${groupId}/${artifactId}"),
            versions: 'WARN: exceeded upper limt to resolve versions',
            latestVersion: '*',
        ]
    }
    return artifacts.sort{ it.name }
}

def resolveVersions = { artifact ->
    // retrieving all versions
    artifact.versions = source.getAllElements('class', ~/versionbutton release/).collect { it.content }

    // pick up latest version. I trust the order of versions in result page.
    artifact.latestVersion = (artifact.versions) ? artifact.versions[0] : '?'
    return artifact
}

def resolveAllVersions = { artifacts ->
    if (opt.v) {
        final PARALLEL_THREAD_COUNT = 5     // multiplicity (a number of thread)
        final MAX_RETRIEVABLE_VERSIONS = 10 // to prevent unexpected DoS attack
        GParsPool.withPool(PARALLEL_THREAD_COUNT) {
            artifacts[0..<([MAX_RETRIEVABLE_VERSIONS, artifacts.size()].min())].eachParallel { artifact ->
                resolveVersions(artifact)
            }
        }
    }
    return artifacts
}

// --------------------------------------
// Main
// --------------------------------------
resolveAllVersions(retrieveArtifacts()).each { printArtifact it }

