package com.ambient.tv.modules

/** Resolves the exact signed dependency graph before any executable is loaded. */
internal object SystemModuleDependencies {
    fun orderSelected(
        root: SystemModule,
        selected: SystemModuleStore.Package,
        resolve: (SystemModule) -> SystemModuleStore.Package?,
    ): List<SystemModuleStore.Package> =
        order(root) { module -> if (module == root) selected else resolve(module) }

    fun order(
        root: SystemModule,
        resolve: (SystemModule) -> SystemModuleStore.Package?,
    ): List<SystemModuleStore.Package> {
        val visiting = mutableSetOf<SystemModule>()
        val resolved = linkedMapOf<SystemModule, SystemModuleStore.Package>()
        fun visit(module: SystemModule, expectedVersion: Long?) {
            if (module in visiting) throw SystemModuleTrustException("Cyclic system module dependency")
            resolved[module]?.let {
                if (expectedVersion != null && it.build.version != expectedVersion)
                    throw SystemModuleTrustException("Conflicting system module dependency versions")
                return
            }
            val pkg = resolve(module) ?: throw SystemModuleTrustException("Required module not installed: ${module.id}")
            if (pkg.build.module != module.id ||
                (expectedVersion != null && pkg.build.version != expectedVersion))
                throw SystemModuleTrustException("Incompatible module dependency: ${module.id}")
            visiting += module
            for (dependency in pkg.build.dependencies) {
                val child = SystemModule.fromId(dependency.module)
                    ?: throw SystemModuleTrustException("Unknown module dependency")
                visit(child, dependency.version)
            }
            visiting -= module
            resolved[module] = pkg
        }
        visit(root, null)
        return resolved.values.toList()
    }
}
