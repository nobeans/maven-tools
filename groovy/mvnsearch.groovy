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
import groovyx.gpars.*
import static groovyx.gpars.actor.Actors.*

import java.util.concurrent.*

// -----------------------------------
// Handle arguments
// -----------------------------------
def command = System.properties.thisCommand ?: 'groovy mvnsearch.groovy'
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
// Prepare printing
// ---------------------
def printArtifact = {
    def printRichFormat = { artifact, mainPart ->
        println "-"*60
        println ">> ${artifact.name}"
        println mainPart
        if (opt.v) println "versions: " + artifact.versions
        if (opt.u) println artifact.url
    }
    def latestVersion = { artifact ->
        // I trust the order of versions in result page
        return (artifact.versions?.size() > 0) ? artifact.versions[0] : '?'
    }

    if (opt.p) return { artifact ->
        def writer = new StringWriter()
        new groovy.xml.MarkupBuilder(writer).dependency {
            groupId(artifact.groupId)
            artifactId(artifact.artifactId)
            if (opt.v) version(latestVersion(artifact))
        }
        printRichFormat artifact, writer
    }
    if (opt.g) return { artifact ->
        def version = (opt.v) ? latestVersion(artifact) : '*'
        printRichFormat artifact, """@Grab("${artifact.groupId}:${artifact.artifactId}:${version}")"""
    }
    return { artifact ->
        println "${artifact.name} - ${artifact.groupId}:${artifact.artifactId}" + ((opt.u) ? " - ${artifact.url}" : "")
    }
}.call()

// --------------------------------------
// Prepare retrieving
// --------------------------------------
def retrieveArtifacts = {
    def artifacts = []
    def queryUrl = "http://mvnrepository.com/search.html?query=" + keywords.join('+')
    new XmlParser(new SAXParser()).parse(queryUrl).'**'.P.findAll{ it.@class == 'result' }.flatten().each { p -> // for each found artifact
        def a = p.A[0]
        def (all, groupId, artifactId) = (a.@href =~ '^/artifact/([^/]+)/([^/]+)$')[0]
        artifacts << [name:a.text(), groupId:groupId, artifactId:artifactId]
    }
    return artifacts
}
def resolveVersions = { artifact ->
    if (opt.v) {
        def artifactUrl = "http://mvnrepository.com/artifact/${artifact.groupId}/${artifact.artifactId}"
        artifact.versions = new XmlParser(new SAXParser()).parse(artifactUrl).'**'.TABLE.findAll{ it.@class == 'grid' }.
            '**'.TR.flatten().collect { tr -> tr.TD?.getAt(0)?.collect { it.text() }?.getAt(0) }.findAll{ it != null }
    }
    return artifact
}

// --------------------------------------
// Main (with concurrency)
// --------------------------------------
def queue = new LinkedBlockingQueue()
def artifacts = retrieveArtifacts()
actor {
    Asynchronizer.doParallel(5) {
        artifacts.each { artifact ->
            queue << { resolveVersions(artifact) }.callAsync()
        }
    }
}
artifacts.size().times {
    printArtifact queue.take().get()
}

