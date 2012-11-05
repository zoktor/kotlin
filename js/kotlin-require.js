"use strict";
Kotlin.defineModule = function (id, moduleDependencies, definitionFunction) {
    define(id, moduleDependencies || [], definitionFunction)
};