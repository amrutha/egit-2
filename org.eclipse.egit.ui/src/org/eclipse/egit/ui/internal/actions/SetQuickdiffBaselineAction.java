/*******************************************************************************
 * Copyright (C) 2008, Robin Rosenberg <robin.rosenberg@dewire.com>
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.egit.ui.internal.actions;

import java.util.List;

import org.eclipse.egit.core.op.IEGitOperation;
import org.eclipse.egit.ui.UIText;
import org.eclipse.jgit.revwalk.RevCommit;

/**
 * Changes the reference for the quickdiff
 */
public class SetQuickdiffBaselineAction extends AbstractRevCommitOperationAction {

	@Override
	protected IEGitOperation createOperation(List<RevCommit> selection) {
		return new QuickdiffBaselineOperation(getActiveRepository(), selection.get(0).getId().name());
	}

	@Override
	protected String getJobName() {
		return UIText.SetQuickdiffBaselineAction_setQuickdiffBaseline;
	}

}
