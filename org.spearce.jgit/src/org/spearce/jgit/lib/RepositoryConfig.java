/*
 *  Copyright (C) 2006  Shawn Pearce <spearce@spearce.org>
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public
 *  License, version 2, as published by the Free Software Foundation.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public
 *  License along with this library; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 */
package org.spearce.jgit.lib;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class RepositoryConfig {
	private final Repository repo;

	private final File configFile;

	private CoreConfig core;

	private List entries;

	private Map byName;

	private Map lastInEntry;

	private Map lastInGroup;
	
	private static final String MAGIC_EMPTY_VALUE = "%%magic%%empty%%";

	// used for global configs
	private RepositoryConfig() {
		repo = null;
		configFile = new File(System.getProperty("user.home"), ".gitconfig");
		clear();
		if (configFile.exists()) {
			try {
				load();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	private static RepositoryConfig globalConfig = null;
	
	public static RepositoryConfig getGlobalConfig() {
		if (globalConfig == null) 
			globalConfig = new RepositoryConfig();
		return globalConfig;
	}
	
	protected RepositoryConfig(final Repository r) {
		repo = r;
		configFile = new File(repo.getDirectory(), "config");
		clear();
	}

	public CoreConfig getCore() {
		return core;
	}

	public int getInt(final String group, final String name,
			final int defaultValue) {
		final String n = getString(group, name);
		if (n == null) {
			if (repo == null)
				return defaultValue;
			return getGlobalConfig().getInt(group, name, defaultValue);
		}

		try {
			return Integer.parseInt(n);
		} catch (NumberFormatException nfe) {
			throw new IllegalArgumentException("Invalid integer value: "
					+ group + "." + name + "=" + n);
		}
	}

	public boolean getBoolean(final String group, final String name,
			final boolean defaultValue) {
		String n = getRawString(group, name);
		if (n == null) {
			if (repo == null)
				return defaultValue;
			return getGlobalConfig().getBoolean(group, name, defaultValue);
		}

		n = n.toLowerCase();
		if (MAGIC_EMPTY_VALUE.equals(n) || "yes".equals(n) || "true".equals(n) || "1".equals(n)) {
			return true;
		} else if ("no".equals(n) || "false".equals(n) || "0".equals(n)) {
			return false;
		} else {
			throw new IllegalArgumentException("Invalid boolean value: "
					+ group + "." + name + "=" + n);
		}
	}

	public String getString(final String group, final String name) {
		String val = getRawString(group, name);
		if (MAGIC_EMPTY_VALUE.equals(val)) {
			return "";
		}
		return val;
	}
	
	private String getRawString(final String group, final String name) {
		final Object o;
		o = byName.get(group.toLowerCase() + "." + name.toLowerCase());
		if (o instanceof List) {
			return ((Entry) ((List) o).get(0)).value;
		} else if (o instanceof Entry) {
			return ((Entry) o).value;
		} else {
			if (repo == null)
				return null;
			return getGlobalConfig().getString(group, name);
		}
	}

	public void create() {
		Entry e;

		clear();

		e = new Entry();
		e.base = "core";
		add(e);

		e = new Entry();
		e.base = "core";
		e.name = "repositoryformatversion";
		e.value = "0";
		add(e);

		e = new Entry();
		e.base = "core";
		e.name = "filemode";
		e.value = "true";
		add(e);

		core = new CoreConfig(this);
	}

	public void save() throws IOException {
		final File tmp = new File(configFile.getParentFile(), configFile
				.getName()
				+ ".lock");
		final PrintWriter r = new PrintWriter(new BufferedWriter(
				new OutputStreamWriter(new FileOutputStream(tmp),
						Constants.CHARACTER_ENCODING)));
		boolean ok = false;
		try {
			final Iterator i = entries.iterator();
			while (i.hasNext()) {
				final Entry e = (Entry) i.next();
				if (e.prefix != null) {
					r.print(e.prefix);
				}
				if (e.base != null && e.name == null) {
					r.print('[');
					r.print(e.base);
					if (e.extendedBase != null) {
						r.print(' ');
						r.print('"');
						r.print(escapeValue(e.extendedBase));
						r.print('"');
					}
					r.print(']');
				} else if (e.base != null && e.name != null) {
					if (e.prefix == null || "".equals(e.prefix)) {
						r.print('\t');
					}
					r.print(e.name);
					if (e.value != null) {
						if (!MAGIC_EMPTY_VALUE.equals(e.value)) {
							r.print(" = ");
							r.print(escapeValue(e.value));
						}
					}
					if (e.suffix != null) {
						r.print(' ');
					}
				}
				if (e.suffix != null) {
					r.print(e.suffix);
				}
				r.println();
			}
			ok = true;
		} finally {
			r.close();
			if (ok)
				if (!tmp.renameTo(configFile))
					if (configFile.exists() && configFile.delete())
						if (!tmp.renameTo(configFile))
							throw new IOException("Cannot update config file");
			if (tmp.exists())
				tmp.delete();
		}
	}

	public void load() throws IOException {
		clear();
		final BufferedReader r = new BufferedReader(new InputStreamReader(
				new FileInputStream(configFile), Constants.CHARACTER_ENCODING));
		try {
			Entry last = null;
			Entry e = new Entry();
			for (;;) {
				r.mark(1);
				int input = r.read();
				final char in = (char) input;
				if (-1 == input) {
					break;
				} else if ('\n' == in) {
					// End of this entry.
					add(e);
					if (e.base != null) {
						last = e;
					}
					e = new Entry();
				} else if (e.suffix != null) {
					// Everything up until the end-of-line is in the suffix.
					e.suffix += in;
				} else if (';' == in || '#' == in) {
					// The rest of this line is a comment; put into suffix.
					e.suffix = String.valueOf(in);
				} else if (e.base == null && Character.isWhitespace(in)) {
					// Save the leading whitespace (if any).
					if (e.prefix == null) {
						e.prefix = "";
					}
					e.prefix += in;
				} else if ('[' == in) {
					// This is a group header line.
					e.base = readBase(r);
					input = r.read();
					if ('"' == input) {
						e.extendedBase = readValue(r, true, '"');
						input = r.read();
					}
					if (']' == input) {
						e.extendedBase = null;
					} else {
						throw new IOException("Bad group header.");
					}
					e.suffix = "";
				} else if (last != null) {
					// Read a value.
					e.base = last.base;
					e.extendedBase = last.extendedBase;
					r.reset();
					e.name = readName(r);
					if (e.name.endsWith("\n")) {
						e.name = e.name.substring(0, e.name.length()-1);
						e.value = MAGIC_EMPTY_VALUE;
					} else 
						e.value = readValue(r, false, -1);
				} else {
					throw new IOException("Invalid line in config file.");
				}
			}
		} finally {
			r.close();
		}

		core = new CoreConfig(this);
	}

	private void clear() {
		entries = new ArrayList();
		byName = new HashMap();
		lastInEntry = new HashMap();
		lastInGroup = new HashMap();
	}

	private void add(final Entry e) {
		entries.add(e);
		if (e.base != null) {
			final String b = e.base.toLowerCase();
			final String group;
			if (e.extendedBase != null) {
				group = b + "." + e.extendedBase;
			} else {
				group = b;
			}
			if (e.name != null) {
				final String n = e.name.toLowerCase();
				final String key = group + "." + n;
				final Object o = byName.get(key);
				if (o == null) {
					byName.put(key, e);
				} else if (o instanceof Entry) {
					final ArrayList l = new ArrayList();
					l.add(o);
					l.add(e);
					byName.put(key, l);
				} else if (o instanceof List) {
					((List) o).add(e);
				}
				lastInEntry.put(key, e);
			}
			lastInGroup.put(group, e);
		}
	}

	private static String escapeValue(final String x) {
		boolean inquote = false;
		int lineStart = 0;
		final StringBuffer r = new StringBuffer(x.length());
		for (int k = 0; k < x.length(); k++) {
			final char c = x.charAt(k);
			switch (c) {
			case '\n':
				if (inquote) {
					r.append('"');
					inquote = false;
				}
				r.append("\\n\\\n");
				lineStart = r.length();
				break;

			case '\t':
				r.append("\\t");
				break;

			case '\b':
				r.append("\\b");
				break;

			case '\\':
				r.append("\\\\");
				break;

			case '"':
				r.append("\\\"");
				break;

			case ';':
			case '#':
				if (!inquote) {
					r.insert(lineStart, '"');
					inquote = true;
				}
				r.append(c);
				break;

			case ' ':
				if (!inquote && r.length() > 0
						&& r.charAt(r.length() - 1) == ' ') {
					r.insert(lineStart, '"');
					inquote = true;
				}
				r.append(' ');
				break;

			default:
				r.append(c);
				break;
			}
		}
		if (inquote) {
			r.append('"');
		}
		return r.toString();
	}

	private static String readBase(final BufferedReader r) throws IOException {
		final StringBuffer base = new StringBuffer();
		for (;;) {
			r.mark(1);
			int c = r.read();
			if (c < 0) {
				throw new IOException("Unexpected end of config file.");
			} else if (']' == c) {
				r.reset();
				break;
			} else if (' ' == c || '\t' == c) {
				for (;;) {
					r.mark(1);
					c = r.read();
					if (c < 0) {
						throw new IOException("Unexpected end of config file.");
					} else if ('"' == c) {
						r.reset();
						break;
					} else if (' ' == c || '\t' == c) {
						// Skipped...
					} else {
						throw new IOException("Bad base entry. : " + base + "," + c);
					}
				}
				break;
			} else if (Character.isLetterOrDigit((char) c) || '.' == c || '-' == c) {
				base.append((char) c);
			} else {
				throw new IOException("Bad base entry. : " + base + ", " + c);
			}
		}
		return base.toString();
	}

	private static String readName(final BufferedReader r) throws IOException {
		final StringBuffer name = new StringBuffer();
		for (;;) {
			r.mark(1);
			int c = r.read();
			if (c < 0) {
				throw new IOException("Unexpected end of config file.");
			} else if ('=' == c) {
				break;
			} else if (' ' == c || '\t' == c) {
				for (;;) {
					r.mark(1);
					c = r.read();
					if (c < 0) {
						throw new IOException("Unexpected end of config file.");
					} else if ('=' == c) {
						break;
					} else if (';' == c || '#' == c || '\n' == c) {
						r.reset();
						break;
					} else if (' ' == c || '\t' == c) {
						// Skipped...
					} else {
						throw new IOException("Bad entry delimiter.");
					}
				}
				break;
			} else if (Character.isLetterOrDigit((char) c)) {
				name.append((char) c);
			} else if ('\n' == c) {
				r.reset();
				name.append((char) c);
				break;
			} else {
				throw new IOException("Bad config entry name: " + name + (char) c);
			}
		}
		return name.toString();
	}

	private static String readValue(final BufferedReader r, boolean quote,
			final int eol) throws IOException {
		final StringBuffer value = new StringBuffer();
		boolean space = false;
		for (;;) {
			r.mark(1);
			int c = r.read();
			if (c < 0) {
				throw new IOException("Unexpected end of config file.");
			}
			if ('\n' == c) {
				if (quote) {
					throw new IOException("Newline in quotes not allowed.");
				}
				r.reset();
				break;
			}
			if (eol == c) {
				break;
			}
			if (!quote) {
				if (Character.isWhitespace((char) c)) {
					space = true;
					continue;
				}
				if (';' == c || '#' == c) {
					r.reset();
					break;
				}
			}
			if (space) {
				if (value.length() > 0) {
					value.append(' ');
				}
				space = false;
			}
			if ('\\' == c) {
				c = r.read();
				switch (c) {
				case -1:
					throw new IOException("End of file in escape.");
				case '\n':
					continue;
				case 't':
					value.append('\t');
					continue;
				case 'b':
					value.append('\b');
					continue;
				case 'n':
					value.append('\n');
					continue;
				case '\\':
					value.append('\\');
					continue;
				case '"':
					value.append('"');
					continue;
				default:
					throw new IOException("Bad escape: " + ((char) c));
				}
			}
			if ('"' == c) {
				quote = !quote;
				continue;
			}
			value.append((char) c);
		}
		return value.length() > 0 ? value.toString() : null;
	}

	public String toString() {
		return "RepositoryConfig[" + configFile.getPath() + "]";
	}

	static class Entry {
		String prefix;

		String base;

		String extendedBase;

		String name;

		String value;

		String suffix;
	}
	
	RepositoryConfig(String file) throws IOException  {
		repo = null;
		configFile = new File(file);
		clear();
		load();
	}
}
