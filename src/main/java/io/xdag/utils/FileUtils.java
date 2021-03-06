/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020-2030 The XdagJ Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.xdag.utils;

import static org.apache.commons.lang3.StringUtils.isBlank;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

public class FileUtils {

    public static List<String> recursiveList(String path) throws IOException {
        final List<String> files = new ArrayList<>();
        Files.walkFileTree(
                Paths.get(path),
                new FileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        files.add(file.toString());
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
        return files;
    }

    public static boolean recursiveDelete(String fileName) {
        File file = new File(fileName);
        if (file.exists()) {
            // check if the file is a directory
            if (file.isDirectory()) {
                if ((file.list()).length > 0) {
                    for (String s : file.list()) {
                        // call deletion of file individually
                        recursiveDelete(fileName + System.getProperty("file.separator") + s);
                    }
                }
            }

            file.setWritable(true);
            boolean result = file.delete();
            return result;
        } else {
            return false;
        }
    }

    public static byte[] readDnetDat(File file) throws IOException {
        FileInputStream inputStream = new FileInputStream(file);
        byte[] buffer = new byte[2048];
        try {
            while (true) {
                int len = inputStream.read(buffer);
                if (len == -1) {
                    break;
                }
            }
        } finally {
            inputStream.close();
        }
        return buffer;
    }

    /**
     * ?????????????????????????????????suffix?????????
     * <p>???????????????</p>
     *
     * @param dirPath ????????????
     * @param suffix ?????????
     * @param isRecursive ????????????????????????
     * @return ????????????
     */
    public static List<File> listFilesInDirWithFilter(String dirPath, String suffix, boolean isRecursive) {
        return listFilesInDirWithFilter(getFileByPath(dirPath), suffix, isRecursive);
    }

    /**
     * ?????????????????????????????????suffix?????????
     * <p>???????????????</p>
     *
     * @param dir ??????
     * @param suffix ?????????
     * @param isRecursive ????????????????????????
     * @return ????????????
     */
    public static List<File> listFilesInDirWithFilter(File dir, String suffix, boolean isRecursive) {
        if (isRecursive) {
            return listFilesInDirWithFilter(dir, suffix);
        }
        if (dir == null || !isDir(dir)) {
            return null;
        }
        List<File> list = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files != null && files.length != 0) {
            for (File file : files) {
                if (file.getName().toUpperCase().endsWith(suffix.toUpperCase())) {
                    list.add(file);
                }
            }
        }
        return list;
    }

    /**
     * ?????????????????????????????????suffix????????????????????????
     * <p>???????????????</p>
     *
     * @param dirPath ????????????
     * @param suffix ?????????
     * @return ????????????
     */
    public static List<File> listFilesInDirWithFilter(String dirPath, String suffix) {
        return listFilesInDirWithFilter(getFileByPath(dirPath), suffix);
    }

    /**
     * ?????????????????????????????????suffix????????????????????????
     * <p>???????????????</p>
     *
     * @param dir ??????
     * @param suffix ?????????
     * @return ????????????
     */
    public static List<File> listFilesInDirWithFilter(File dir, String suffix) {
        if (dir == null || !isDir(dir)) {
            return null;
        }
        List<File> list = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files != null && files.length != 0) {
            for (File file : files) {
                if (file.getName().toUpperCase().endsWith(suffix.toUpperCase())) {
                    list.add(file);
                }
                if (file.isDirectory()) {
                    list.addAll(listFilesInDirWithFilter(file, suffix));
                }
            }
        }
        return list;
    }

    /**
     * ???????????????????????????filter?????????
     *
     * @param dirPath ????????????
     * @param filter ?????????
     * @param isRecursive ????????????????????????
     * @return ????????????
     */
    public static List<File> listFilesInDirWithFilter(String dirPath, FilenameFilter filter, boolean isRecursive) {
        return listFilesInDirWithFilter(getFileByPath(dirPath), filter, isRecursive);
    }

    /**
     * ???????????????????????????filter?????????
     *
     * @param dir ??????
     * @param filter ?????????
     * @param isRecursive ????????????????????????
     * @return ????????????
     */
    public static List<File> listFilesInDirWithFilter(File dir, FilenameFilter filter, boolean isRecursive) {
        if (isRecursive) {
            return listFilesInDirWithFilter(dir, filter);
        }
        if (dir == null || !isDir(dir)) {
            return null;
        }
        List<File> list = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files != null && files.length != 0) {
            for (File file : files) {
                if (filter.accept(file.getParentFile(), file.getName())) {
                    list.add(file);
                }
            }
        }
        return list;
    }

    /**
     * ???????????????????????????filter????????????????????????
     *
     * @param dirPath ????????????
     * @param filter ?????????
     * @return ????????????
     */
    public static List<File> listFilesInDirWithFilter(String dirPath, FilenameFilter filter) {
        return listFilesInDirWithFilter(getFileByPath(dirPath), filter);
    }

    /**
     * ???????????????????????????filter????????????????????????
     *
     * @param dir ??????
     * @param filter ?????????
     * @return ????????????
     */
    public static List<File> listFilesInDirWithFilter(File dir, FilenameFilter filter) {
        if (dir == null || !isDir(dir)) {
            return null;
        }
        List<File> list = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files != null && files.length != 0) {
            for (File file : files) {
                if (filter.accept(file.getParentFile(), file.getName())) {
                    list.add(file);
                }
                if (file.isDirectory()) {
                    list.addAll(listFilesInDirWithFilter(file, filter));
                }
            }
        }
        return list;
    }

    /**
     * ??????????????????????????????
     *
     * @param filePath ????????????
     * @return ??????
     */
    public static File getFileByPath(String filePath) {
        return isBlank(filePath) ? null : new File(filePath);
    }

    /**
     * ????????????????????????
     *
     * @param filePath ????????????
     * @return {@code true}: ??????<br>{@code false}: ?????????
     */
    public static boolean isFileExists(String filePath) {
        return isFileExists(getFileByPath(filePath));
    }

    /**
     * ????????????????????????
     *
     * @param file ??????
     * @return {@code true}: ??????<br>{@code false}: ?????????
     */
    public static boolean isFileExists(File file) {
        return file != null && file.exists();
    }

    /**
     * ?????????????????????
     *
     * @param dirPath ????????????
     * @return {@code true}: ???<br>{@code false}: ???
     */
    public static boolean isDir(String dirPath) {
        return isDir(getFileByPath(dirPath));
    }

    /**
     * ?????????????????????
     *
     * @param file ??????
     * @return {@code true}: ???<br>{@code false}: ???
     */
    public static boolean isDir(File file) {
        return isFileExists(file) && file.isDirectory();
    }

    /**
     * ?????????????????????
     *
     * @param filePath ????????????
     * @return {@code true}: ???<br>{@code false}: ???
     */
    public static boolean isFile(String filePath) {
        return isFile(getFileByPath(filePath));
    }

    /**
     * ?????????????????????
     *
     * @param file ??????
     * @return {@code true}: ???<br>{@code false}: ???
     */
    public static boolean isFile(File file) {
        return isFileExists(file) && file.isFile();
    }

}
