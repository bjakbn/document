// 文件位置：document/app/src/main/java/com/example/doctree/MainActivity.kt
package com.example.doctree

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var inputTree: EditText
    private lateinit var generateBtn: Button
    private val REQUEST_MANAGE_STORAGE = 1001
    private val REQUEST_WRITE_STORAGE = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inputTree = findViewById(R.id.inputTree)
        generateBtn = findViewById(R.id.generateBtn)

        generateBtn.setOnClickListener {
            if (checkPermission()) {
                generateFileTree()
            } else {
                requestPermission()
            }
        }
    }

    private fun checkPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivityForResult(intent, REQUEST_MANAGE_STORAGE)
            } catch (e: Exception) {
                // 如果无法启动设置界面，直接打开应用详情
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_WRITE_STORAGE
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MANAGE_STORAGE) {
            if (checkPermission()) {
                generateFileTree()
            } else {
                Toast.makeText(this, "需要所有文件访问权限才能保存ZIP", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_WRITE_STORAGE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                generateFileTree()
            } else {
                Toast.makeText(this, "需要存储权限", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun generateFileTree() {
        val treeText = inputTree.text.toString().trim()
        if (treeText.isEmpty()) {
            Toast.makeText(this, "请输入文件树", Toast.LENGTH_SHORT).show()
            return
        }

        // 禁用按钮，防止重复点击
        generateBtn.isEnabled = false
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. 在缓存目录创建临时根目录
                val tmpRoot = File(cacheDir, "tree_gen_${System.currentTimeMillis()}")
                tmpRoot.mkdirs()

                // 2. 解析并生成文件树
                parseAndCreate(treeText, tmpRoot)

                // 3. 打包为ZIP
                val zipFile = File(cacheDir, "output_${System.currentTimeMillis()}.zip")
                zipDirectory(tmpRoot, zipFile)

                // 4. 复制到公共存储
                val publicDir = Environment.getExternalStorageDirectory()  // /storage/emulated/0
                val destFile = File(publicDir, "file_tree_${System.currentTimeMillis()}.zip")
                zipFile.copyTo(destFile, overwrite = true)

                // 5. 清理临时文件
                tmpRoot.deleteRecursively()
                zipFile.delete()

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "ZIP 已保存至: ${destFile.absolutePath}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "错误: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    generateBtn.isEnabled = true
                }
            }
        }
    }

    /**
     * 解析树形文本，在 rootDir 下创建对应目录和空文件
     */
    private fun parseAndCreate(text: String, rootDir: File) {
        val lines = text.lines().filter { it.isNotBlank() }
        // 栈中存储当前层级对应的目录文件，索引0为根（rootDir）
        val stack = mutableListOf(rootDir)

        for (line in lines) {
            // 计算深度：以4个空格或"│   "为一个缩进单元
            var depth = 0
            var i = 0
            while (i < line.length) {
                val sub = line.substring(i)
                if (sub.startsWith("│   ") || sub.startsWith("    ")) {
                    depth++
                    i += 4
                } else {
                    break
                }
            }

            // 提取名称：移除可能的树形符号 "├── " 或 "└── "
            var name = line.substring(i).trim()
            if (name.startsWith("├── ") || name.startsWith("└── ")) {
                name = name.substring(4).trim()
            } else if (name.startsWith("├──") || name.startsWith("└──")) {
                // 兼容无空格的情况
                name = name.substring(3).trim()
            }

            if (name.isEmpty()) continue

            // 调整栈深度
            while (stack.size > depth + 1) {
                stack.removeAt(stack.lastIndex)
            }
            // 确保栈深度至少为 depth+1
            while (stack.size <= depth) {
                stack.add(stack.last())
            }

            val parentDir = stack.last()

            if (name.endsWith("/")) {
                // 目录：去除末尾斜杠
                val dirName = name.removeSuffix("/")
                val newDir = File(parentDir, dirName)
                newDir.mkdirs()
                // 将新目录压入栈，供下一层使用
                if (stack.size > depth + 1) {
                    stack[depth + 1] = newDir
                } else {
                    stack.add(newDir)
                }
            } else {
                // 文件
                val file = File(parentDir, name)
                file.parentFile?.mkdirs()
                file.createNewFile()
                // 如果下一行深度更深，说明这个名称其实是一个目录，但用户未加斜杠
                // 简单处理：不修改栈，等待后续判断？这里暂不处理。
            }
        }
    }

    /**
     * 递归压缩目录
     */
    private fun zipDirectory(sourceDir: File, zipFile: File) {
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            sourceDir.walkTopDown().forEach { file ->
                val entryName = file.relativeTo(sourceDir).path.let {
                    if (file.isDirectory && !it.endsWith("/")) "$it/" else it
                }
                if (entryName.isEmpty()) return@forEach
                zos.putNextEntry(ZipEntry(entryName))
                if (file.isFile) {
                    FileInputStream(file).copyTo(zos)
                }
                zos.closeEntry()
            }
        }
    }
}
