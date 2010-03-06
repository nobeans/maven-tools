/*
 * Copyright 2010 the original author or authors.
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

@Grab("net.sourceforge.nekohtml:nekohtml:1.9.14")
import org.cyberneko.html.parsers.SAXParser

@GrabResolver(name="jboss", root="http://repository.jboss.org/maven2/")
@Grab("org.codehaus.gpars:gpars:0.9")
import groovyx.gpars.Asynchronizer
import static groovyx.gpars.actor.Actors.*

import java.util.concurrent.LinkedBlockingQueue

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
if (opt.v && !(opt.g || opt.p)) cli.die '--with-version must be specfied with --format-xxxxx'
def keywords = opt.arguments()
if (keywords.size() < 1) cli.die 'KEYWORD must be specified'

// ---------------------
// Prepare closures
// ---------------------
def printArtifact = {
    def printRichFormat = { artifact, mainPart ->
        println "-"*60
        println ">> ${artifact.name}"
        println mainPart
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
        printRichFormat artifact, writer
    }
    if (opt.g) return { artifact ->
        def version = (opt.v) ? artifact.latestVersion : '*'
        printRichFormat artifact, """@Grab("${artifact.groupId}:${artifact.artifactId}:${version}")"""
    }
    return { artifact ->
        println "${artifact.name} - ${artifact.groupId}:${artifact.artifactId}" + ((opt.u) ? " - ${artifact.url}" : "")
    }
}.call()

def retrieveArtifacts = {
    def artifacts = []
    def queryUrl = "http://mvnrepository.com/search.html?query=" + keywords.join('+')
    new XmlParser(new SAXParser()).parse(queryUrl).'**'.P.findAll{ it.@class == 'result' }.flatten().each { p -> // for each found artifact
        def a = p.A[0]
        def (all, groupId, artifactId) = (a.@href =~ '^/artifact/([^/]+)/([^/]+)$')[0]
        artifacts << [
            name: a.text(),
            groupId: groupId,
            artifactId: artifactId,
            url: "http://mvnrepository.com/artifact/${groupId}/${artifactId}",
        ]
    }
    return artifacts
}

def resolveVersions = { artifact ->
    if (opt.v) {
        // retrieving all versions
        artifact.versions = new XmlParser(new SAXParser()).parse(artifact.url).'**'.TABLE.findAll{ it.@class == 'grid' }.
            '**'.TR.flatten().collect { tr -> tr.TD?.getAt(0)?.collect { it.text() }?.getAt(0) }.findAll{ it != null }

        // pick up latest version. I trust the order of versions in result page.
        artifact.latestVersion = (artifact.versions) ? artifact.versions[0] : '?'
    }
    return artifact
}

// --------------------------------------
// Main (with concurrency)
// --------------------------------------
def queue = new LinkedBlockingQueue()
def artifacts = retrieveArtifacts()
actor {
    Asynchronizer.doParallel(5) { // multiplicity (number of thread)
        artifacts.each { artifact ->
            queue << { resolveVersions(artifact) }.callAsync()
        }
    }
}
artifacts.size().times {
    printArtifact queue.take().get()
}

