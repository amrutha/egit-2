/*
 *  Copyright (C) 20067  Robin Rosenberg
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
package org.spearce.jgit.errors;

import java.io.IOException;

/**
 * JGit encountered a case that it knows it cannot yet handle.
 */
public class NotSupportedException extends IOException {
	private static final long serialVersionUID = 1L;

	/**
	 * Construct a NotSupportedException for some issue JGit cannot
	 * yet handle.
	 *
	 * @param s message describing the issue
	 */
	public NotSupportedException(final String s) {
		super(s);
	}

	/**
	 * Construct a NotSupportedException for some issue JGit cannot yet handle.
	 * 
	 * @param s
	 *            message describing the issue
	 * @param why
	 *            a lower level implementation specific issue.
	 */
	public NotSupportedException(final String s, final Throwable why) {
		super(s);
		initCause(why);
	}
}
