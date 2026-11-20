// core-plan-solve: a plan as the solver reads it (GraphSolve: the plan model to the kernel's SolveInput, its resources
// numbered by ResourceKey as the website's adapter numbers them).
dependencies {
    "api"(project(":core-model"))
    // javax.annotation.Nullable, as the mod uses it; annotations only, not shipped.
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    testCompileOnly("com.google.code.findbugs:jsr305:3.0.2")
}
