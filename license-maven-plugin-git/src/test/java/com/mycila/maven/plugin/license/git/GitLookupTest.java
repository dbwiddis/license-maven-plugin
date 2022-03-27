/*
 * Copyright (C) 2008-2021 Mycila (mathieu.carbou@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.mycila.maven.plugin.license.git;

import com.mycila.maven.plugin.license.git.GitLookup.DateSource;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Set;
import java.util.TimeZone;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static java.util.Collections.emptySet;

/**
 * @author <a href="mailto:ppalaga@redhat.com">Peter Palaga</a>
 */
class GitLookupTest {

  private static Path gitRepoRoot;

  @TempDir
  static File tempFolder;

  @BeforeAll
  static void beforeClass() throws IOException {
    URL url = GitLookupTest.class.getResource("git-test-repo.zip");
    gitRepoRoot = Paths.get(tempFolder.toPath() + File.separator + "git-test-repo");
    unzip(url, tempFolder.toPath());
  }

  static void unzip(URL url, Path unzipDestination) throws IOException {
    ZipInputStream zipInputStream = null;
    try {
      zipInputStream = new ZipInputStream(new BufferedInputStream(url.openStream()));
      ZipEntry entry;
      byte[] buffer = new byte[2048];
      while ((entry = zipInputStream.getNextEntry()) != null) {

        String fileName = entry.getName();
        Path unzippedFile = Paths.get(unzipDestination.toAbsolutePath() + File.separator + fileName);
        if (entry.isDirectory()) {
          unzippedFile.toFile().mkdirs();
        } else {
          unzippedFile.toFile().getParentFile().mkdirs();
          OutputStream out = null;
          try {
            out = new BufferedOutputStream(new FileOutputStream(unzippedFile.toFile()), 2048);
            int len;
            while ((len = zipInputStream.read(buffer)) != -1) {
              out.write(buffer, 0, len);
            }
          } finally {
            if (out != null) {
              out.close();
            }
          }
        }
      }
    } finally {
      if (zipInputStream != null) {
        zipInputStream.close();
      }
    }
  }

  @Test
  void modified() throws GitAPIException, IOException {
    assertLastChange(newAuthorLookup(), "dir1/file1.txt", 2006);
    assertLastChange(newCommitterLookup(), "dir1/file1.txt", 2006);

    assertCreation(newAuthorLookup(), "dir1/file1.txt", 2000);
    assertCreation(newCommitterLookup(), "dir1/file1.txt", 2000);
  }

  @Test
  void justCreated() throws GitAPIException, IOException {
    assertLastChange(newAuthorLookup(), "dir2/file2.txt", 2007);
    assertLastChange(newCommitterLookup(), "dir2/file2.txt", 2007);

    assertCreation(newAuthorLookup(), "dir2/file2.txt", 2007);
    assertCreation(newCommitterLookup(), "dir2/file2.txt", 2007);
  }

  @Test
  void moved() throws GitAPIException, IOException {
    assertLastChange(newAuthorLookup(), "dir1/file3.txt", 2009);
    assertLastChange(newCommitterLookup(), "dir1/file3.txt", 2010);

    // In this case the file moved and its creation data could not be tracked
    assertCreation(newAuthorLookup(), "dir1/file3.txt", 2009);
    assertCreation(newCommitterLookup(), "dir1/file3.txt", 2010);
  }

  @Test
  void newUnstaged() throws GitAPIException, IOException {
    int currentYear = getCurrentGmtYear();
    assertLastChange(newAuthorLookup(), "dir1/file5.txt", currentYear);
    assertLastChange(newCommitterLookup(), "dir1/file5.txt", currentYear);

    assertCreation(newAuthorLookup(), "dir1/file5.txt", currentYear);
    assertCreation(newCommitterLookup(), "dir1/file5.txt", currentYear);
  }

  @Test
  void newStaged() throws GitAPIException, IOException {
    int currentYear = getCurrentGmtYear();
    assertLastChange(newAuthorLookup(), "dir1/file6.txt", currentYear);
    assertLastChange(newCommitterLookup(), "dir1/file6.txt", currentYear);

    assertCreation(newAuthorLookup(), "dir1/file6.txt", currentYear);
    assertCreation(newCommitterLookup(), "dir1/file6.txt", currentYear);
  }

  /**
   * @return
   */
  private int getCurrentGmtYear() {
    Calendar result = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
    result.setTimeInMillis(System.currentTimeMillis());
    return result.get(Calendar.YEAR);
  }

  @Test
  void reuseProvider() throws GitAPIException, IOException {
    GitLookup provider = newAuthorLookup();
    assertLastChange(provider, "dir1/file1.txt", 2006);
    assertLastChange(provider, "dir2/file2.txt", 2007);
    assertLastChange(provider, "dir1/file3.txt", 2009);
  }

  @Test
  void timezone() throws GitAPIException, IOException {
    try {
      new GitLookup(gitRepoRoot.toFile(), DateSource.AUTHOR, TimeZone.getTimeZone("GMT"), 10, emptySet());
      Assertions.fail("RuntimeException expected");
    } catch (RuntimeException e) {
      if (e.getMessage().startsWith("Time zone must be null with dateSource " + DateSource.AUTHOR.name() + "")) {
        /* expected */
      } else {
        throw e;
      }
    }

    /* null is GMT */
    GitLookup nullTzLookup = new GitLookup(gitRepoRoot.toFile(), DateSource.COMMITER, null, 10, emptySet());
    assertLastChange(nullTzLookup, "dir1/file3.txt", 2010);

    /* explicit GMT */
    GitLookup gmtLookup = new GitLookup(gitRepoRoot.toFile(), DateSource.COMMITER, TimeZone.getTimeZone("GMT"), 10, emptySet());
    assertLastChange(gmtLookup, "dir1/file3.txt", 2010);

    /*
     * explicit non-GMT zome. Note that the relevant commit's (GMT) time stamp is 2010-12-31T23:30:00 which yealds
     * 2011 in the CET (+01:00) time zone
     */
    GitLookup cetLookup = new GitLookup(gitRepoRoot.toFile(), DateSource.COMMITER, TimeZone.getTimeZone("CET"), 10, emptySet());
    assertLastChange(cetLookup, "dir1/file3.txt", 2011);

  }

  @Test
  public void ignoreCommitsInLastChange() throws GitAPIException, IOException {
    assertLastChange(newAuthorLookup("95d52919cbe340dc271cf1f5ec68cf36705bd3a3"), "dir1/file1.txt", 2004);
    assertLastChange(newCommitterLookup("95d52919cbe340dc271cf1f5ec68cf36705bd3a3"), "dir1/file1.txt", 2004);
  }

  @Test
  public void doNotIgnoreCommitsInCreation() throws GitAPIException, IOException {
    assertCreation(newAuthorLookup("53b44baedc5a378f9b665da12f298e1003793219"), "dir1/file1.txt", 2000);
    assertCreation(newCommitterLookup("53b44baedc5a378f9b665da12f298e1003793219"), "dir1/file1.txt", 2000);
  }

  private GitLookup newAuthorLookup(String... commitsToIgnore) throws IOException {
    Set<ObjectId> ignoreSet = Arrays.stream(commitsToIgnore)
            .map(ObjectId::fromString)
            .collect(Collectors.toSet());
    return new GitLookup(gitRepoRoot.toFile(), DateSource.AUTHOR, null, 10, ignoreSet);
  }

  private GitLookup newCommitterLookup(String... commitsToIgnore) throws IOException {
    Set<ObjectId> ignoreSet = Arrays.stream(commitsToIgnore)
            .map(ObjectId::fromString)
            .collect(Collectors.toSet());
    return new GitLookup(gitRepoRoot.toFile(), DateSource.COMMITER, null, 10, ignoreSet);
  }

  private void assertLastChange(GitLookup provider, String relativePath, int expected) throws
      GitAPIException, IOException {
    int actual = provider.getYearOfLastChange(Paths.get(gitRepoRoot + File.separator
        + relativePath.replace('/', File.separatorChar)).toFile());
    Assertions.assertEquals(expected, actual);
  }

  private void assertCreation(GitLookup provider, String relativePath, int expected) throws
      GitAPIException, IOException {
    int actual = provider.getYearOfCreation(Paths.get(gitRepoRoot + File.separator
        + relativePath.replace('/', File.separatorChar)).toFile());
    Assertions.assertEquals(expected, actual);
  }

}
