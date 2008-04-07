package org.spearce.jgit.lib;

import java.io.File;
import java.io.IOException;

/**
 * Test reading of git config
 */
public class RepositoryConfigTest extends RepositoryTestCase {
	/**
	 * Read config item with no value from a section without a subsection.
	 *
	 * @throws IOException
	 */
	public void test001_ReadBareKey() throws IOException {
		String path = writeTrashFile("config_001", "[foo]\nbar\n").getAbsolutePath();
		RepositoryConfig repositoryConfig = new RepositoryConfig(path);
		System.out.println(repositoryConfig.getString("foo", null, "bar"));
		assertEquals(true, repositoryConfig.getBoolean("foo", null, "bar", false));
		assertEquals("", repositoryConfig.getString("foo", null, "bar"));
	}

	/**
	 * Read various data from a subsection.
	 *
	 * @throws IOException
	 */
	public void test002_ReadWithSubsection() throws IOException {
		String path = writeTrashFile("config_002", "[foo \"zip\"]\nbar\n[foo \"zap\"]\nbar=false\nn=3\n").getAbsolutePath();
		RepositoryConfig repositoryConfig = new RepositoryConfig(path);
		assertEquals(true, repositoryConfig.getBoolean("foo", "zip", "bar", false));
		assertEquals("", repositoryConfig.getString("foo","zip", "bar"));
		assertEquals(false, repositoryConfig.getBoolean("foo", "zap", "bar", true));
		assertEquals("false", repositoryConfig.getString("foo", "zap", "bar"));
		assertEquals(3, repositoryConfig.getInt("foo", "zap", "n", 4));
		assertEquals(4, repositoryConfig.getInt("foo", "zap","m", 4));
	}

	public void test003_PutRemote() throws IOException {
		File cfgFile = writeTrashFile("config_003", "");
		String path = cfgFile.getAbsolutePath();
		RepositoryConfig repositoryConfig = new RepositoryConfig(path);
		repositoryConfig.putString("sec", "ext", "name", "value");
		repositoryConfig.putString("sec", "ext", "name2", "value2");
		repositoryConfig.save();
		checkFile(cfgFile, "[sec \"ext\"]\n\tname = value\n\tname2 = value2\n");
	}

	public void test004_PutSimple() throws IOException {
		File cfgFile = writeTrashFile("config_004", "");
		String path = cfgFile.getAbsolutePath();
		RepositoryConfig repositoryConfig = new RepositoryConfig(path);
		repositoryConfig.putString("my", null, "somename", "false");
		repositoryConfig.save();
		checkFile(cfgFile, "[my]\n\tsomename = false\n");
	}
}
