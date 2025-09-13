package com.example.gradle.plugin

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.AdviceAdapter

/**
 * 一个 Gradle 插件，用于应用字节码转换。
 */
abstract class DesugarTransformPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val androidComponents = project.extensions.getByType(AndroidComponentsExtension::class.java)

        androidComponents.onVariants { variant ->
            // **【错误修正】**
            // 简化插件注册，不再动态传递参数，以避免编译器解析错误。
            variant.instrumentation.transformClassesWith(
                ReadAllBytesClassVisitorFactory::class.java,
                InstrumentationScope.ALL
            ) {}
        }
    }
}

/**
 * **【错误修正】**
 * 工厂类不再需要自定义参数。我们使用 InstrumentationParameters.None。
 */
abstract class ReadAllBytesClassVisitorFactory : AsmClassVisitorFactory<InstrumentationParameters.None> {

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor {
        // **【错误修正】**
        // 直接为 ASM 提供一个稳定、兼容的 API 版本，而不是动态获取。
        // Opcodes.ASM9 是一个安全且现代的选择。
        return ReadAllBytesClassVisitor(Opcodes.ASM9, nextClassVisitor)
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        return when {
            // 跳过系统库（根据包名前缀过滤）
            classData.className.startsWith("android/") -> false
            classData.className.startsWith("androidx/") -> false
            classData.className.startsWith("com/android/") -> false
            classData.className.startsWith("java/") -> false
            classData.className.startsWith("javax/") -> false
            classData.className.startsWith("kotlin/") -> false
            classData.className.startsWith("kotlinx/") -> false
            classData.className.startsWith("org/intellij/") -> false
            classData.className.startsWith("org/jetbrains/") -> false
            classData.className.startsWith("com/google/") -> false
            // 其他需要跳过的包...
            else -> true
        }
    }
}

/**
 * 这个 ClassVisitor 会遍历一个类的所有方法。
 */
private class ReadAllBytesClassVisitor(
    api: Int, // 参数现在直接就是 ASM API level
    classVisitor: ClassVisitor
) : ClassVisitor(api, classVisitor) { // 直接用于初始化父类

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        val originalMethodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions)
        // 使用从父类继承的 'api' 字段
        return ReadAllBytesMethodVisitor(this.api, originalMethodVisitor, access, name, descriptor)
    }
}

/**
 * 这个 MethodVisitor 检查方法体内的每一条指令，并替换目标方法调用。
 */
private class ReadAllBytesMethodVisitor(
    api: Int,
    methodVisitor: MethodVisitor,
    access: Int,
    name: String?,
    descriptor: String?
) : AdviceAdapter(api, methodVisitor, access, name, descriptor) {

    override fun visitMethodInsn(
        opcode: Int,
        owner: String?,
        name: String?,
        descriptor: String?,
        isInterface: Boolean
    ) {
        if (
            opcode == Opcodes.INVOKEVIRTUAL &&
            owner == "java/io/InputStream" &&
            name == "readAllBytes" &&
            descriptor == "()[B"
        ) {
            println("ASM-Transform: Replacing 'InputStream.readAllBytes()' call in method '${this.name}'")
            super.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "com/hirain/aiagent/InputStreamCompat",
                "readAllBytes",
                "(Ljava/io/InputStream;)[B",
                false
            )
        } else {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
        }
    }
}
