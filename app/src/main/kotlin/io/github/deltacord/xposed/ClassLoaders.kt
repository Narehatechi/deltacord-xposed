package io.github.deltacord.xposed

class CompositeClassLoader(
    parent: ClassLoader,
    private val fallbacks: List<ClassLoader>,
) : ClassLoader(parent) {
    override fun findClass(name: String): Class<*> {
        for (loader in fallbacks) {
            try {
                return loader.loadClass(name)
            } catch (_: ClassNotFoundException) {
            }
        }
        throw ClassNotFoundException(name)
    }
}
