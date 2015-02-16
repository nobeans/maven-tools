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

@Grab('org.jsoup:jsoup:1.8.1')
import org.jsoup.Jsoup

import groovyx.gpars.GParsPool
import java.util.concurrent.LinkedBlockingQueue
import java.util.logging.Logger
import java.util.logging.Level

import static groovyx.gpars.actor.Actors.*

// -----------------------------------
// Handle arguments
// -----------------------------------
def command = System.properties.thisCommand ?: 'groovy mvnsearch.groovy' // for wrapper script
def cli = new CliBuilder(usage:"$command [OPTIONS] KEYWORD..[KEYWORD]")
cli.formatter.width = 80
cli.with {
    h longOpt:'help', 'print this help message'
    g longOpt:'format-gr8', 'print Groovy Grape/Gradle/Grails format'
    p longOpt:'format-pom', 'print Maven2 pom format'
    v longOpt:'with-version','with retrieved versions (HEAVY)'
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
def printArtifact = { artifact ->
    if (opt.p) {
        println "-" * 60
        println ">> ${artifact.name} -- ${artifact.url}"

        def writer = new StringWriter()
        new groovy.xml.MarkupBuilder(writer).dependency {
            groupId(artifact.groupId)
            artifactId(artifact.artifactId)
            if (opt.v) version(artifact.latestVersion)
        }
        println writer.toString()

        if (opt.v) println "versions: " + artifact.versions.join(", ")
        return
    }
    if (opt.g) {
        println "-" * 60
        println ">> ${artifact.name} -- ${artifact.url}"

        def version = (opt.v) ? artifact.latestVersion : '*'
        println "\"${artifact.groupId}:${artifact.artifactId}:${version}\""

        if (opt.v) println "versions: " + artifact.versions.join(", ")
        return
    }
    println "${artifact.name} -- ${artifact.groupId}:${artifact.artifactId} -- ${artifact.url}"
}

def retrieveArtifacts = {
    def artifacts = []
    def doc = Jsoup.connect("http://mvnrepository.com/search?q=${keywords.join('+')}").get()
    return doc.select(".im-title > a:first-of-type").collect { element ->
        def (all, groupId, artifactId) = (element.attr("href") =~ '^/artifact/([^/]+)/([^/]+)$')[0]
        return [
            name: element.text(),
            groupId: groupId,
            artifactId: artifactId,
            url: "http://mvnrepository.com${element.attr("href")}",
            versions: ['WARN: exceeded upper limt to resolve versions'],
            latestVersion: '*',
        ]
    }
}

def resolveVersions = { artifact ->
    // retrieving all versions
    artifact.versions = Jsoup.connect(artifact.url).get().select(".vbtn.release")*.text()

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

// suppress a SEVERE log which is emitted because of mvnrepository's HTML is so bad.
Logger.getLogger("net.htmlparser.jericho").level = Level.OFF

resolveAllVersions(retrieveArtifacts()).sort { it.name }.each { printArtifact it }

