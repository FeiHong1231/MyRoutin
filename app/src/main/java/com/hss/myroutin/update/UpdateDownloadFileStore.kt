package com.hss.myroutin.update

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection

/**
 * 说明：更新下载文件存储，统一维护目标 APK、下载分片和 ETag 校验器的配对生命周期。
 *
 * @param updateDirectory 应用缓存目录下专用于更新文件的受限目录
 * @作者 huangssh
 * @版本 2.3
 */
internal class UpdateDownloadFileStore(
    private val updateDirectory: File
) {

    /**
     * 确保更新目录可写并返回当前版本的文件集合；孤立 ETag 不得参与下一次续传。
     * @param versionCode 当前更新版本号
     * @return 当前版本固定命名的目标文件、分片和 ETag 文件
     */
    fun prepare(versionCode: Int): UpdateDownloadFiles {
        if (
            !updateDirectory.isDirectory &&
            !updateDirectory.mkdirs() &&
            !updateDirectory.isDirectory
        ) {
            throw UpdateDirectoryException()
        }
        return filesFor(versionCode).also { files ->
            if (!files.temporaryFile.isFile) {
                files.entityTagFile.delete()
            }
        }
    }

    /**
     * 返回固定目录中的版本文件名，不创建目录，供显式清理和测试准备分片使用。
     * @param versionCode 当前更新版本号
     * @return 当前版本对应的三个缓存文件
     */
    fun filesFor(versionCode: Int): UpdateDownloadFiles {
        return UpdateDownloadFiles(
            targetFile = File(updateDirectory, "MyRoutin-$versionCode.apk"),
            temporaryFile = File(updateDirectory, "MyRoutin-$versionCode.download"),
            entityTagFile = File(updateDirectory, "MyRoutin-$versionCode.etag")
        )
    }

    /**
     * 读取旧分片的强 ETag；文件损坏时删除校验器并退化为仅校验 Content-Range。
     * @param files 当前版本对应的下载文件
     * @return 可用于 If-Range 的强 ETag
     */
    fun readCachedEntityTag(files: UpdateDownloadFiles): String? {
        val entityTag = runCatching { files.entityTagFile.readText() }
            .getOrNull()
            .let(UpdateResumeProtocol::normalizeStrongEntityTag)
        if (entityTag == null) {
            files.entityTagFile.delete()
        }
        return entityTag
    }

    /**
     * 将当前响应的强 ETag 与分片配套保存；无有效校验器时清理旧文件。
     * @param connection 当前 APK 下载响应
     * @param files 当前版本对应的下载文件
     */
    fun persistResponseEntityTag(connection: HttpURLConnection, files: UpdateDownloadFiles) {
        val entityTag = UpdateResumeProtocol.normalizeStrongEntityTag(
            connection.getHeaderField(ENTITY_TAG_HEADER)
        )
        if (entityTag == null) {
            files.entityTagFile.delete()
            return
        }
        runCatching { files.entityTagFile.writeText(entityTag) }
            .onFailure { files.entityTagFile.delete() }
    }

    /**
     * 显式取消或不可恢复错误同时删除 APK 分片和实体校验器，禁止下次使用孤立状态续传。
     * @param files 当前版本对应的下载文件
     */
    fun deletePartial(files: UpdateDownloadFiles) {
        files.temporaryFile.delete()
        files.entityTagFile.delete()
    }

    /**
     * 删除指定版本的分片状态，用户明确关闭暂停卡片时调用。
     * @param versionCode 当前更新版本号
     */
    fun deletePartial(versionCode: Int) {
        deletePartial(filesFor(versionCode))
    }

    /**
     * 将通过摘要校验的临时文件原子切换为安装文件，替换失败时交由上层统一报告。
     * @param files 当前版本对应的下载文件
     */
    fun publishVerifiedDownload(files: UpdateDownloadFiles) {
        if (files.targetFile.exists() && !files.targetFile.delete()) {
            throw IOException("无法替换旧更新文件")
        }
        if (!files.temporaryFile.renameTo(files.targetFile)) {
            throw IOException("无法保存更新文件")
        }
        files.entityTagFile.delete()
    }

    /**
     * 仅允许删除当前更新目录中的已校验 APK，避免外部文件路径进入清理逻辑。
     * @param apkFile 已校验并用于安装的缓存文件
     */
    fun deleteCachedUpdate(apkFile: File) {
        if (apkFile.parentFile == updateDirectory) {
            apkFile.delete()
        }
    }

    private companion object {
        private const val ENTITY_TAG_HEADER = "ETag"
    }
}

/**
 * 说明：同一更新版本的目标 APK、未完成分片和实体校验器，三者必须按同一生命周期管理。
 *
 * @作者 huangssh
 * @版本 2.3
 */
internal data class UpdateDownloadFiles(
    val targetFile: File,
    val temporaryFile: File,
    val entityTagFile: File
)

/** 更新缓存目录无法创建时使用，使页面保留原有可行动错误文案。 */
internal class UpdateDirectoryException : IOException()
