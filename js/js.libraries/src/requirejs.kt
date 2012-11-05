package org.requirejs

native fun define(name:String, dependencies:Array<String>, functionDefinition:()->Any) = noImpl
native fun require(name:String):Any = noImpl