/*******************************************************************************
 * Copyright (c) 2010, SAP AG
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Stefan Lay (SAP AG) - initial implementation
 *******************************************************************************/
package org.eclipse.egit.core.op;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.TimeZone;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.egit.core.CoreText;
import org.eclipse.egit.core.internal.trace.GitTraceLocation;
import org.eclipse.egit.core.project.RepositoryMapping;
import org.eclipse.jgit.lib.Commit;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.GitIndex;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectWriter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.Tree;
import org.eclipse.jgit.lib.TreeEntry;
import org.eclipse.jgit.lib.GitIndex.Entry;
import org.eclipse.osgi.util.NLS;
import org.eclipse.team.core.TeamException;

/**
 * This class implements the commit of a list of files.
 */
public class CommitOperation implements IEGitOperation {

	private IFile[] filesToCommit;

	private boolean commitWorkingDirChanges = false;

	private String author;

	private String committer;

	private String message;

	private boolean amending = false;

	// needed for amending
	private Commit previousCommit;

	// needed for amending
	private Repository[] repos;

	private ArrayList<IFile> notIndexed;

	private ArrayList<IFile> notTracked;

	/**
	 *
	 * @param filesToCommit
	 *            a list of files which will be included in the commit
	 * @param notIndexed
	 *            a list of all files with changes not in the index
	 * @param notTracked
	 *            a list of all untracked files
	 * @param author
	 *            the author of the commit
	 * @param committer
	 *            the committer of the commit
	 * @param message
	 *            the commit message
	 */
	public CommitOperation(IFile[] filesToCommit, ArrayList<IFile> notIndexed,
			ArrayList<IFile> notTracked, String author, String committer,
			String message) {
		this.filesToCommit = filesToCommit;
		this.notIndexed = notIndexed;
		this.notTracked = notTracked;
		this.author = author;
		this.committer = committer;
		this.message = message;
	}

	public void execute(IProgressMonitor m) throws CoreException {
		IProgressMonitor monitor;
		if (m == null)
			monitor = new NullProgressMonitor();
		else
			monitor = m;
		IWorkspaceRunnable action = new IWorkspaceRunnable() {

			public void run(IProgressMonitor monitor) throws CoreException {
				if (amending || filesToCommit != null
						&& filesToCommit.length > 0) {
					monitor.beginTask(
							CoreText.CommitOperation_PerformingCommit,
							filesToCommit.length * 2);
					monitor
							.setTaskName(CoreText.CommitOperation_PerformingCommit);
					HashMap<Repository, Tree> treeMap = new HashMap<Repository, Tree>();
					try {
						if (!prepareTrees(filesToCommit, treeMap, monitor)) {
							// reread the indexes, they were changed in memory
							for (Repository repo : treeMap.keySet())
								repo.getIndex().read();
							return;
						}
					} catch (IOException e) {
						throw new TeamException(
								CoreText.CommitOperation_errorPreparingTrees, e);
					}

					try {
						doCommits(message, treeMap);
						monitor.worked(filesToCommit.length);
					} catch (IOException e) {
						throw new TeamException(
								CoreText.CommitOperation_errorCommittingChanges,
								e);
					}
				} else if (commitWorkingDirChanges) {
					// TODO commit -a
				} else {
					// TODO commit
				}
			}
		};
		ResourcesPlugin.getWorkspace().run(action, monitor);
	}

	public ISchedulingRule getSchedulingRule() {
		return ResourcesPlugin.getWorkspace().getRoot();
	}

	private boolean prepareTrees(IFile[] selectedItems,
			HashMap<Repository, Tree> treeMap, IProgressMonitor monitor)
			throws IOException, UnsupportedEncodingException {
		if (selectedItems.length == 0) {
			// amending commit - need to put something into the map
			for (Repository repo : repos) {
				treeMap.put(repo, repo.mapTree(Constants.HEAD));
			}
		}

		for (IFile file : selectedItems) {

			if (monitor.isCanceled())
				return false;
			monitor.worked(1);

			IProject project = file.getProject();
			RepositoryMapping repositoryMapping = RepositoryMapping
					.getMapping(project);
			Repository repository = repositoryMapping.getRepository();
			Tree projTree = treeMap.get(repository);
			if (projTree == null) {
				projTree = repository.mapTree(Constants.HEAD);
				if (projTree == null)
					projTree = new Tree(repository);
				treeMap.put(repository, projTree);
				// TODO is this the right Location?
				if (GitTraceLocation.CORE.isActive())
					GitTraceLocation.getTrace().trace(
							GitTraceLocation.CORE.getLocation(),
							"Orig tree id: " + projTree.getId()); //$NON-NLS-1$
			}
			GitIndex index = repository.getIndex();
			String repoRelativePath = repositoryMapping
					.getRepoRelativePath(file);
			String string = repoRelativePath;

			TreeEntry treeMember = projTree.findBlobMember(repoRelativePath);
			// we always want to delete it from the current tree, since if it's
			// updated, we'll add it again
			if (treeMember != null)
				treeMember.delete();

			Entry idxEntry = index.getEntry(string);
			if (notIndexed.contains(file)) {
				File thisfile = new File(repositoryMapping.getWorkDir(),
						idxEntry.getName());
				if (!thisfile.isFile()) {
					index.remove(repositoryMapping.getWorkDir(), thisfile);
					// TODO is this the right Location?
					if (GitTraceLocation.CORE.isActive())
						GitTraceLocation.getTrace().trace(
								GitTraceLocation.CORE.getLocation(),
								"Phantom file, so removing from index"); //$NON-NLS-1$
					continue;
				} else {
					idxEntry.update(thisfile);
				}
			}
			if (notTracked.contains(file)) {
				idxEntry = index.add(repositoryMapping.getWorkDir(), new File(
						repositoryMapping.getWorkDir(), repoRelativePath));

			}

			if (idxEntry != null) {
				projTree.addFile(repoRelativePath);
				TreeEntry newMember = projTree.findBlobMember(repoRelativePath);

				newMember.setId(idxEntry.getObjectId());
				// TODO is this the right Location?
				if (GitTraceLocation.CORE.isActive())
					GitTraceLocation.getTrace().trace(
							GitTraceLocation.CORE.getLocation(),
							"New member id for " + repoRelativePath //$NON-NLS-1$
									+ ": " + newMember.getId() + " idx id: " //$NON-NLS-1$ //$NON-NLS-2$
									+ idxEntry.getObjectId());
			}
		}
		return true;
	}

	private void doCommits(String commitMessage,
			HashMap<Repository, Tree> treeMap) throws IOException,
			TeamException {

		final Date commitDate = new Date();
		final TimeZone timeZone = TimeZone.getDefault();

		final PersonIdent authorIdent = new PersonIdent(author);
		final PersonIdent committerIdent = new PersonIdent(committer);

		for (java.util.Map.Entry<Repository, Tree> entry : treeMap.entrySet()) {
			Tree tree = entry.getValue();
			Repository repo = tree.getRepository();
			repo.getIndex().write();
			writeTreeWithSubTrees(tree);

			ObjectId currentHeadId = repo.resolve(Constants.HEAD);
			ObjectId[] parentIds;
			if (amending) {
				parentIds = previousCommit.getParentIds();
			} else {
				if (currentHeadId != null)
					parentIds = new ObjectId[] { currentHeadId };
				else
					parentIds = new ObjectId[0];
			}
			Commit commit = new Commit(repo, parentIds);
			commit.setTree(tree);
			commit.setMessage(commitMessage);
			commit
					.setAuthor(new PersonIdent(authorIdent, commitDate,
							timeZone));
			commit.setCommitter(new PersonIdent(committerIdent, commitDate,
					timeZone));

			ObjectWriter writer = new ObjectWriter(repo);
			commit.setCommitId(writer.writeCommit(commit));

			final RefUpdate ru = repo.updateRef(Constants.HEAD);
			ru.setNewObjectId(commit.getCommitId());
			ru.setRefLogMessage(buildReflogMessage(commitMessage), false);
			if (ru.forceUpdate() == RefUpdate.Result.LOCK_FAILURE) {
				throw new TeamException(NLS.bind(
						CoreText.CommitOperation_failedToUpdate, ru.getName(),
						commit.getCommitId()));
			}
		}
	}

	private void writeTreeWithSubTrees(Tree tree) throws TeamException {
		if (tree.getId() == null) {
			// TODO is this the right Location?
			if (GitTraceLocation.CORE.isActive())
				GitTraceLocation.getTrace().trace(
						GitTraceLocation.CORE.getLocation(),
						"writing tree for: " + tree.getFullName()); //$NON-NLS-1$
			try {
				for (TreeEntry entry : tree.members()) {
					if (entry.isModified()) {
						if (entry instanceof Tree) {
							writeTreeWithSubTrees((Tree) entry);
						} else {
							// this shouldn't happen.... not quite sure what to
							// do here :)
							// TODO is this the right Location?
							if (GitTraceLocation.CORE.isActive())
								GitTraceLocation.getTrace().trace(
										GitTraceLocation.CORE.getLocation(),
										"BAD JUJU: " //$NON-NLS-1$
												+ entry.getFullName());
						}
					}
				}
				ObjectWriter writer = new ObjectWriter(tree.getRepository());
				tree.setId(writer.writeTree(tree));
			} catch (IOException e) {
				throw new TeamException(
						CoreText.CommitOperation_errorWritingTrees, e);
			}
		}
	}

	private String buildReflogMessage(String commitMessage) {
		String firstLine = commitMessage;
		int newlineIndex = commitMessage.indexOf("\n"); //$NON-NLS-1$
		if (newlineIndex > 0) {
			firstLine = commitMessage.substring(0, newlineIndex);
		}
		String commitStr = amending ? "commit (amend):" : "commit: "; //$NON-NLS-1$ //$NON-NLS-2$
		String message = commitStr + firstLine;
		return message;
	}

	/**
	 *
	 * @param amending
	 */
	public void setAmending(boolean amending) {
		this.amending = amending;
	}

	/**
	 *
	 * @param previousCommit
	 */
	public void setPreviousCommit(Commit previousCommit) {
		this.previousCommit = previousCommit;
	}

	/**
	 *
	 * @param repos
	 */
	public void setRepos(Repository[] repos) {
		this.repos = repos;
	}

}
