package me.ele.lancet.plugin.internal;

import com.android.build.api.transform.JarInput;
import com.android.build.api.transform.QualifiedContent;
import com.android.build.api.transform.Status;
import com.android.utils.FileUtils;
import com.google.common.io.Files;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import me.ele.lancet.plugin.Util;
import me.ele.lancet.plugin.internal.context.ClassFetcher;
import me.ele.lancet.weaver.ClassData;
import me.ele.lancet.weaver.Weaver;
import me.ele.lancet.weaver.internal.log.Log;

/**
 * Created by gengwanpeng on 17/5/4.
 */
public class TransformProcessor implements ClassFetcher {

    private final TransformContext context;
    private final Weaver weaver;
    private final DirectoryRunner dirRunner = new DirectoryRunner();
    private Map<QualifiedContent, JarRunner> map = new ConcurrentHashMap<>();

    public TransformProcessor(TransformContext context, Weaver weaver) {
        this.context = context;
        this.weaver = weaver;
    }

    @Override
    public boolean onStart(QualifiedContent content) throws IOException {
        if (content instanceof JarInput) {
            JarInput jarInput = (JarInput) content;
            File targetFile = context.getRelativeFile(content);

            // lancet-base 是编译期/运行期基础库，本身不会被织入，且旧版 D8 对其 class 有兼容性问题。
            // 对该 jar，我们在 transform 阶段直接丢弃，不参与后续 dex 编译（annotations/Origin/This 仅在编译期使用，一般不会在运行期反射）。
            if (isLancetBaseJar(jarInput)) {
                switch (jarInput.getStatus()) {
                    case REMOVED:
                        FileUtils.deleteIfExists(targetFile);
                        break;
                    case CHANGED:
                    default:
                        FileUtils.deleteIfExists(targetFile);
                        break;
                }
                // 返回 false，JarContentProvider 将不会遍历该 jar 的 entry，也不会在输出中生成对应 jar。
                return false;
            }

            switch (jarInput.getStatus()) {
                case REMOVED:
                    FileUtils.deleteIfExists(targetFile);
                    return false;
                case CHANGED:
                    FileUtils.deleteIfExists(targetFile);
                default:
                    Files.createParentDirs(targetFile);
                    map.put(content, new JarRunner(content, targetFile));
            }
        }
        return true;
    }

    @Override
    public void onClassFetch(QualifiedContent content, Status status, String relativePath, byte[] bytes) throws IOException {
        if (content instanceof JarInput) {
            JarRunner jarRunner = map.get(content);
            jarRunner.run(relativePath, bytes);
        } else { // directory, so must be class
            File relativeRoot = context.getRelativeFile(content);
            File target = Util.toSystemDependentFile(relativeRoot, relativePath);
            File hookWithTarget = Util.toSystemDependentHookFile(relativeRoot, relativePath);
            switch (status) {
                case REMOVED:
                    FileUtils.deleteIfExists(target);
                    FileUtils.deleteIfExists(hookWithTarget);
                    break;
                case CHANGED:
                    FileUtils.deleteIfExists(target);
                    FileUtils.deleteIfExists(hookWithTarget);
                default:
                    dirRunner.run(relativeRoot, relativePath, bytes);
            }
        }
    }

    @Override
    public void onComplete(QualifiedContent content) throws IOException {
        if (content instanceof JarInput && ((JarInput) content).getStatus() != Status.REMOVED) {
            JarRunner runner = map.get(content);
            if (runner != null) {
                runner.close();
            }
        }
    }

    class JarRunner implements Closeable {

        private final JarOutputStream jos;
        private final QualifiedContent content;

        JarRunner(QualifiedContent content, File targetFile) throws IOException {
            this.content = content;
            this.jos = new JarOutputStream(
                    new BufferedOutputStream(new FileOutputStream(targetFile)));
        }

        void run(String relativePath, byte[] bytes) throws IOException {
            if (!relativePath.endsWith(".class")) {
                ZipEntry entry = new ZipEntry(relativePath);
                jos.putNextEntry(entry);
                jos.write(bytes);
            } else if (shouldBypassWeaving(relativePath)) {
                // 基础库 class：不做织入，也不写入输出 jar（等同于 provided 依赖）
                return;
            } else {
                for (ClassData classData : weaver.weave(bytes, relativePath)) {
                    ZipEntry entry = new ZipEntry(classData.getClassName() + ".class");
                    jos.putNextEntry(entry);
                    jos.write(classData.getClassBytes());
                }
            }
        }

        public void close() throws IOException {
            jos.close();
        }
    }

    class DirectoryRunner {

        void run(File relativeRoot, String relativePath, byte[] bytes) throws IOException {
            if (shouldBypassWeaving(relativePath)) {
                // 基础库 class：在目录输出中也不写入，保持其为纯编译期依赖
                return;
            }

            for (ClassData data : weaver.weave(bytes, relativePath)) {
                File target = Util.toSystemDependentFile(relativeRoot, data.getClassName() + ".class");
                Files.createParentDirs(target);
                Files.write(data.getClassBytes(), target);
            }
        }
    }

    private static boolean isLancetBaseJar(JarInput jarInput) {
        String name = jarInput.getFile().getName();
        // 通过文件名判断，目前发布的 artifact 形如 lancet-base-<version>.jar
        return name.startsWith("lancet-base-");
    }

    private static boolean shouldBypassWeaving(String relativePath) {
        // 路径以 jar entry 形式出现，统一用 '/'
        return relativePath.startsWith("me/ele/lancet/base/");
    }
}
