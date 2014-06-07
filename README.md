# Mvnclj

A Clojure library (or a quick hack if you prefer) designed to simulate some of the most mundane Maven tasks. This library **is not** Maven and it will never be. I use it mainly to speed-up my development cycle which requires several runs to Maven to compile package and install artifacts since it runs much much faster than maven when used from the REPL. For instance, an small Java library takes about 11 seconds to compile and package using maven

```
masm@fashion$ time mvn -Dmaven.test.skip=true package > /dev/null 2>&1
mvn -Dmaven.test.skip=true package > /dev/null 2>&1  11.53s user 0.36s system 220% cpu 5.398 total
```

While using mvnclj the same task goes to subsecond

```
user=> (time (package my-project))
"Elapsed time: 520.825801 msecs"
true
user=>
```

So, if you are constantly running maven to compile, package and install Java code and you find yourself opening or reloading the browser tab with twitter (or Hacker News, Slashdot, imgur, call your poison) while maven runs, this library may be for you.

## Usage

In the clojure REPL:

```
(use 'mvnclj.core)

;; Define your project. If the pom.xml changes you may need to
;; re-run this. This part includes the dependency resolution phase
;; (which takes time) so is preferable to do just once.
(def my-project  (project "../foo/pom.xml")

;; Just run the compiler
(compile-sources my-project)
; ... Warnings and errors (if any) will appear here

;; Package the project (creates the jar)
(package my-project)

;; Install the project to local repo
(install my-project)
```

## Gotchas

Currently there is support to read and simulate two Maven plugins: jar and compiler. This was implemented to add entries to the Manifest and to configure the source and target options passed to the Java compiler. Nothing else is supported.

Only source files that have changed since the last compilation will be recompiled. A full recompilation requires a clean before compile.

If in doubt, look at the source.

## See Also

[Pomegranate](https://github.com/cemerick/pomegranate). A sane Clojure API for Sonatype [Aether](https://github.com/sonatype/sonatype-aether).


## License

Copyright © 2014
Distributed under the Eclipse Public License, the same as Clojure.
