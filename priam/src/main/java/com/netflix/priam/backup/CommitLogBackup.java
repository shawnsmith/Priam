package com.netflix.priam.backup;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.name.Named;
import com.netflix.priam.backup.AbstractBackupPath.BackupFileType;
import com.netflix.priam.utils.RetryableCallable;


//Providing this if we want to use it outside Quartz
public class CommitLogBackup
{
  private static final Logger logger = LoggerFactory.getLogger(CommitLogBackup.class);
  private final Provider<AbstractBackupPath> pathFactory;
  static List<IMessageObserver> observers = new ArrayList<IMessageObserver>();
  private final List<String> clRemotePaths = new ArrayList<String>();
  private final IBackupFileSystem fs;

  @Inject
  public CommitLogBackup(Provider<AbstractBackupPath> pathFactory, @Named("backup") IBackupFileSystem fs)
  {
    this.pathFactory = pathFactory;
    this.fs = fs;
  }

  public List<AbstractBackupPath> upload(String archivedDir, final String snapshotName)
    throws Exception
  {
    logger.info("Inside upload CommitLog files");

    if (StringUtils.isBlank(archivedDir))
    {
    	throw new IllegalArgumentException("The archived commitlog director is blank or null");
    }
    
    File archivedCommitLogDir = new File(archivedDir);
    if (!archivedCommitLogDir.exists())
    {
       throw new IllegalArgumentException("The archived commitlog director does not exist: " + archivedDir);
    }

    if (logger.isDebugEnabled()) {
      logger.debug("Scanning for backup in: {}", archivedCommitLogDir.getAbsolutePath());
    }
    List<AbstractBackupPath> bps = Lists.newArrayList();
    for (final File file : archivedCommitLogDir.listFiles())
    {
      logger.debug("Uploading commit log {} for backup", file.getCanonicalFile());
      try
      {
        AbstractBackupPath abp = (AbstractBackupPath)new RetryableCallable(3, 100L)
        {
          public AbstractBackupPath retriableCall() throws Exception
          {

            AbstractBackupPath bp = pathFactory.get();
            bp.parseLocal(file, BackupFileType.CL);
            if (snapshotName != null)
            	bp.time = bp.parseDate(snapshotName);
            upload(bp);
            file.delete(); //TODO: should we put delete call here? We don't want to delete if the upload operation fails
            return bp;
          }
        }
        .call();

        bps.add(abp);

        addToRemotePath(abp.getRemotePath());
      }
      catch (Exception e)
      {
        logger.error("Failed to upload local file {}. Ignoring to continue with rest of backup.", file, e);
      }
    }
    return bps;
  }

  private void upload(final AbstractBackupPath bp)
    throws Exception
  {
    new RetryableCallable()
    {
      public Void retriableCall()
        throws Exception
      {
        fs.upload(bp, bp.localReader());
        return null;
      }
    }
    .call();
  }

  public static void addObserver(IMessageObserver observer)
  {
    observers.add(observer);
  }

  public static void removeObserver(IMessageObserver observer) {
    observers.remove(observer);
  }

  public void notifyObservers() {
    for (IMessageObserver observer : observers)
      if (observer != null) {
        logger.debug("Updating CommitLog observers now ...");
        observer.update(IMessageObserver.BACKUP_MESSAGE_TYPE.COMMITLOG, this.clRemotePaths);
      } else {
        logger.debug("Observer is Null, hence can not notify ...");
      }
  }

  protected void addToRemotePath(String remotePath) {
    this.clRemotePaths.add(remotePath);
  }
}