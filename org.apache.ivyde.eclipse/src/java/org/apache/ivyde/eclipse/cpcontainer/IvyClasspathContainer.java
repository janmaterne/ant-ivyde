/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.apache.ivyde.eclipse.cpcontainer;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.ivy.Ivy;
import org.apache.ivy.core.cache.DefaultRepositoryCacheManager;
import org.apache.ivy.core.cache.RepositoryCacheManager;
import org.apache.ivy.core.event.IvyEvent;
import org.apache.ivy.core.event.IvyListener;
import org.apache.ivy.core.event.download.EndArtifactDownloadEvent;
import org.apache.ivy.core.event.download.PrepareDownloadEvent;
import org.apache.ivy.core.event.download.StartArtifactDownloadEvent;
import org.apache.ivy.core.event.resolve.EndResolveDependencyEvent;
import org.apache.ivy.core.event.resolve.StartResolveDependencyEvent;
import org.apache.ivy.core.module.descriptor.Artifact;
import org.apache.ivy.core.module.descriptor.DefaultArtifact;
import org.apache.ivy.core.module.descriptor.ModuleDescriptor;
import org.apache.ivy.core.module.id.ModuleRevisionId;
import org.apache.ivy.core.report.ArtifactDownloadReport;
import org.apache.ivy.core.report.ResolveReport;
import org.apache.ivy.core.resolve.DownloadOptions;
import org.apache.ivy.core.resolve.ResolveOptions;
import org.apache.ivy.core.retrieve.RetrieveOptions;
import org.apache.ivy.plugins.parser.ModuleDescriptorParserRegistry;
import org.apache.ivy.plugins.report.XmlReportParser;
import org.apache.ivy.plugins.repository.TransferEvent;
import org.apache.ivy.plugins.repository.TransferListener;
import org.apache.ivy.util.Message;
import org.apache.ivyde.eclipse.IvyPlugin;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubProgressMonitor;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathContainer;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.swt.widgets.Display;

/**
 * Eclipse classpath container that will contain the ivy resolved entries.
 */
public class IvyClasspathContainer implements IClasspathContainer {

    private final class IvyResolveJob extends Job implements TransferListener, IvyListener {
        long _expectedTotalLength = 1;

        long _currentLength = 0;

        private IProgressMonitor _monitor;

        private IProgressMonitor _dlmonitor;

        private boolean _usePreviousResolveIfExist;

        private int _workPerArtifact = 100;

        private boolean _notify;

        private Ivy _ivy;

        public IvyResolveJob(boolean usePreviousResolveIfExist, boolean notify) {
            super("Resolve " + _javaProject.getProject().getName() + "/" + conf.ivyXmlPath
                    + " dependencies");
            _usePreviousResolveIfExist = usePreviousResolveIfExist;
            _notify = notify;
            _ivy = IvyPlugin.getIvy(_javaProject, conf.getInheritedIvySettingsPath());
        }

        public void transferProgress(TransferEvent evt) {
            switch (evt.getEventType()) {
                case TransferEvent.TRANSFER_INITIATED:
                    _monitor.setTaskName("downloading " + evt.getResource());
                    break;
                case TransferEvent.TRANSFER_STARTED:
                    _currentLength = 0;
                    if (evt.isTotalLengthSet()) {
                        _expectedTotalLength = evt.getTotalLength();
                        _dlmonitor.beginTask("downloading " + evt.getResource(), 100);
                    }
                    break;
                case TransferEvent.TRANSFER_PROGRESS:
                    if (_expectedTotalLength > 1) {
                        _currentLength += evt.getLength();
                        _dlmonitor.worked((int) (_currentLength * 100 / _expectedTotalLength));
                        _monitor.subTask((_currentLength / 1024) + " / "
                                + (_expectedTotalLength / 1024) + "kB");
                    }
                    break;
                default:
            }
        }

        public void progress(IvyEvent event) {
            if (event instanceof TransferEvent) {
                if (_dlmonitor != null) {
                    transferProgress((TransferEvent) event);
                }
            } else if (event instanceof PrepareDownloadEvent) {
                PrepareDownloadEvent pde = (PrepareDownloadEvent) event;
                Artifact[] artifacts = pde.getArtifacts();
                if (artifacts.length > 0) {
                    _workPerArtifact = 1000 / artifacts.length;
                }
            } else if (event instanceof StartArtifactDownloadEvent) {
                StartArtifactDownloadEvent evt = (StartArtifactDownloadEvent) event;
                _monitor.setTaskName("downloading " + evt.getArtifact());
                if (_dlmonitor != null) {
                    _dlmonitor.done();
                }
                _dlmonitor = new SubProgressMonitor(_monitor, _workPerArtifact);
            } else if (event instanceof EndArtifactDownloadEvent) {
                if (_dlmonitor != null) {
                    _dlmonitor.done();
                }
                _monitor.subTask(" ");
                _dlmonitor = null;
            } else if (event instanceof StartResolveDependencyEvent) {
                StartResolveDependencyEvent ev = (StartResolveDependencyEvent) event;
                _monitor.subTask("resolving "
                        + ev.getDependencyDescriptor().getDependencyRevisionId());
            } else if (event instanceof EndResolveDependencyEvent) {
                _monitor.subTask(" ");
            }
        }

        protected IStatus run(IProgressMonitor monitor) {
            Message.info("resolving dependencies of " + _ivyXmlFile);
            _monitor = monitor;
            final IStatus[] status = new IStatus[1];
            final IClasspathEntry[][] classpathEntries = new IClasspathEntry[1][];

            Thread resolver = new Thread() {
                public void run() {
                    _ivy.pushContext();
                    _ivy.getEventManager().addIvyListener(IvyResolveJob.this);

                    _monitor.beginTask("resolving dependencies", 1000);
                    _monitor.setTaskName("resolving dependencies...");

                    URL ivyURL;
                    try {
                        ivyURL = _ivyXmlFile.toURL();
                    } catch (MalformedURLException e) {
                        status[0] = new Status(IStatus.ERROR, IvyPlugin.ID, IStatus.ERROR,
                                "The path of ivy.xml is not a valid URL", e);
                        return;
                    }

                    String[] confs;
                    Collection/* <ArtifactDownloadReport> */all;
                    List problemMessages;
                    ModuleDescriptor md;

                    // context Classloader hook for commonlogging used by httpclient
                    ClassLoader old = Thread.currentThread().getContextClassLoader();
                    Thread.currentThread().setContextClassLoader(
                        IvyClasspathContainer.class.getClassLoader());
                    try {

                        if (_usePreviousResolveIfExist) {
                            md = ModuleDescriptorParserRegistry.getInstance().parseDescriptor(
                                _ivy.getSettings(), ivyURL, false);
                            if (conf.confs.size() == 1 && "*".equals(conf.confs.get(0))) {
                                confs = md.getConfigurationsNames();
                            } else {
                                confs = (String[]) conf.confs
                                        .toArray(new String[conf.confs.size()]);
                            }

                            all = new LinkedHashSet();

                            problemMessages = new ArrayList();
                            // we check if all required configurations have been
                            // resolved
                            for (int i = 0; i < confs.length; i++) {
                                File report = _ivy.getResolutionCacheManager()
                                        .getConfigurationResolveReportInCache(
                                            ResolveOptions.getDefaultResolveId(md), confs[i]);
                                boolean resolved = false;
                                if (report.exists()) {
                                    // found a report, try to parse it.
                                    try {
                                        XmlReportParser parser = new XmlReportParser();
                                        parser.parse(report);
                                        all.addAll(Arrays.asList(parser.getArtifactReports()));
                                        resolved = true;
                                    } catch (ParseException e) {
                                        Message.info("\n\nIVYDE: Error while parsing the report "
                                                + report
                                                + ". Falling back by doing a resolve again.");
                                        // it fails, so let's try resolving
                                    }
                                }
                                if (!resolved) {
                                    // no resolve previously done for at least
                                    // one conf... we do it now
                                    Message.info("\n\nIVYDE: previous resolve of "
                                            + md.getModuleRevisionId().getModuleId()
                                            + " doesn't contain enough data: resolving again\n");
                                    ResolveReport r = _ivy.resolve(ivyURL, new ResolveOptions()
                                            .setConfs((String[]) conf.confs
                                                    .toArray(new String[conf.confs.size()])));
                                    all.addAll(Arrays.asList(r.getArtifactsReports(null, false)));
                                    confs = r.getConfigurations();
                                    problemMessages.addAll(r.getAllProblemMessages());
                                    maybeRetrieve(md, confs);

                                    break;
                                }
                            }
                        } else {
                            Message.info("\n\nIVYDE: calling resolve on " + ivyURL + "\n");
                            ResolveReport report = _ivy.resolve(ivyURL, new ResolveOptions()
                                    .setConfs((String[]) conf.confs.toArray(new String[conf.confs
                                            .size()])));
                            problemMessages = report.getAllProblemMessages();
                            all = new LinkedHashSet(Arrays.asList(report.getArtifactsReports(null,
                                false)));
                            confs = report.getConfigurations();
                            md = report.getModuleDescriptor();

                            if (_monitor.isCanceled()) {
                                status[0] = Status.CANCEL_STATUS;
                                return;
                            }

                            maybeRetrieve(md, confs);
                        }

                        warnIfDuplicates(all);

                        classpathEntries[0] = artifacts2ClasspathEntries(all);
                    } catch (ParseException e) {
                        String errorMsg = "Error while parsing the ivy file " + _ivyXmlFile + "\n"
                                + e.getMessage();
                        Message.error(errorMsg);
                        status[0] = new Status(Status.ERROR, IvyPlugin.ID, Status.ERROR, errorMsg,
                                e);
                        return;
                    } catch (Exception e) {
                        String errorMsg = "Error while resolving dependencies for " + _ivyXmlFile
                                + "\n" + e.getMessage();
                        Message.error(errorMsg);
                        status[0] = new Status(IStatus.ERROR, IvyPlugin.ID, IStatus.ERROR,
                                errorMsg, e);
                        return;
                    } finally {
                        Thread.currentThread().setContextClassLoader(old);
                        _monitor.done();
                        _ivy.getEventManager().removeIvyListener(IvyResolveJob.this);
                    }

                    if (!problemMessages.isEmpty()) {
                        StringBuffer problems = new StringBuffer();
                        for (Iterator iter = problemMessages.iterator(); iter.hasNext();) {
                            String msg = (String) iter.next();
                            problems.append(msg).append("\n");
                        }
                        status[0] = new Status(
                                Status.ERROR,
                                IvyPlugin.ID,
                                Status.ERROR,
                                "Impossible to resolve dependencies of " + md.getModuleRevisionId()
                                        + ":\n" + problems + "\nSee IvyConsole for further details",
                                null);
                        return;
                    }

                    status[0] = Status.OK_STATUS;
                }
            };

            resolver.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                public void uncaughtException(Thread t, Throwable e) {
                    status[0] = new Status(Status.ERROR, IvyPlugin.ID, Status.ERROR,
                            "The resolve job has unexpectedly stopped", e);
                }
            });

            try {
                resolver.start();
                while (true) {
                    try {
                        resolver.join(100);
                    } catch (InterruptedException e) {
                        _ivy.interrupt(resolver);
                        return Status.CANCEL_STATUS;
                    }
                    synchronized (status) { // ensure proper sharing of done var
                        if (status[0] != null || !resolver.isAlive()) {
                            break;
                        }
                    }
                    if (_monitor.isCanceled()) {
                        _ivy.interrupt(resolver);
                        return Status.CANCEL_STATUS;
                    }
                }
                if (status[0] == Status.OK_STATUS) {
                    updateClasspathEntries(_notify, classpathEntries[0]);
                }
                return status[0];
            } finally {
                synchronized (IvyClasspathContainer.this) {
                    _job = null;
                }
                IvyPlugin.log(IStatus.INFO, "resolved dependencies of " + _ivyXmlFile, null);
            }
        }

        /**
         * Trigger a warn if there are duplicates entries due to configuration conflict.
         * <p>
         * TODO: the algorithm can be more clever and find which configuration are conflicting.
         * 
         * @param all
         *            the resolved artifacts
         */
        private void warnIfDuplicates(Collection/* <ArtifactDownloadReport> */all) {
            ArtifactDownloadReport[] reports = (ArtifactDownloadReport[]) all
                    .toArray(new ArtifactDownloadReport[all.size()]);
            Set duplicates = new HashSet();
            for (int i = 0; i < reports.length - 1; i++) {
                if (accept(reports[i].getArtifact())) {
                    ModuleRevisionId mrid1 = reports[i].getArtifact().getModuleRevisionId();
                    for (int j = i + 1; j < reports.length; j++) {
                        if (accept(reports[j].getArtifact())) {
                            ModuleRevisionId mrid2 = reports[j].getArtifact().getModuleRevisionId();
                            if (mrid1.getModuleId().equals(mrid2.getModuleId())
                                    && !mrid1.getRevision().equals(mrid2.getRevision())) {
                                duplicates.add(mrid1.getModuleId());
                                break;
                            }
                        }
                    }
                }
            }
            if (!duplicates.isEmpty()) {
                StringBuffer buffer = new StringBuffer(
                        "There are some duplicates entries due to conflicts between the resolved configurations "
                                + conf.confs);
                buffer.append(":\n  - ");
                Iterator it = duplicates.iterator();
                while (it.hasNext()) {
                    buffer.append(it.next());
                    if (it.hasNext()) {
                        buffer.append("\n  - ");
                    }
                }
                _ivy.getLoggerEngine().log(buffer.toString(), Message.MSG_WARN);
            }
        }

        private void maybeRetrieve(ModuleDescriptor md, String[] confs) throws IOException {
            if (conf.getInheritedDoRetrieve()) {
                String pattern = _javaProject.getProject().getLocation().toPortableString() + "/"
                        + conf.getInheritedRetrievePattern();
                _monitor.setTaskName("retrieving dependencies in " + pattern);
                _ivy.retrieve(md.getModuleRevisionId(), pattern, new RetrieveOptions()
                        .setConfs(confs));
            }
        }

        private IClasspathEntry[] artifacts2ClasspathEntries(Collection all) {
            IClasspathEntry[] classpathEntries;
            Collection paths = new LinkedHashSet();
            for (Iterator iter = all.iterator(); iter.hasNext();) {
                ArtifactDownloadReport artifact = (ArtifactDownloadReport) iter.next();
                if (artifact.getLocalFile() != null && accept(artifact.getArtifact())) {
                    Path classpathArtifact = new Path(artifact.getLocalFile().getAbsolutePath());
                    Path sourcesArtifact = getSourcesArtifactPath(artifact, all);
                    Path javadocArtifact = getJavadocArtifactPath(artifact, all);
                    paths.add(JavaCore.newLibraryEntry(classpathArtifact, getSourceAttachment(
                        classpathArtifact, sourcesArtifact), getSourceAttachmentRoot(
                        classpathArtifact, sourcesArtifact), null, getExtraAttribute(
                        classpathArtifact, javadocArtifact), false));
                }
            }
            classpathEntries = (IClasspathEntry[]) paths.toArray(new IClasspathEntry[paths.size()]);

            return classpathEntries;
        }

        private Path getSourcesArtifactPath(ArtifactDownloadReport adr, Collection all) {
            Artifact artifact = adr.getArtifact();
            _monitor.subTask("searching sources for " + artifact);
            for (Iterator iter = all.iterator(); iter.hasNext();) {
                ArtifactDownloadReport otherAdr = (ArtifactDownloadReport) iter.next();
                Artifact a = otherAdr.getArtifact();
                if (otherAdr.getLocalFile() != null
                        && isSourceArtifactName(artifact.getName(), a.getName())
                        && a.getId().getRevision().equals(artifact.getId().getRevision())
                        && isSources(a)) {
                    return new Path(otherAdr.getLocalFile().getAbsolutePath());
                }
            }
            if (shouldTestNonDeclaredSources()) {
                return getMetaArtifactPath(adr, "source", "sources");
            } else {
                return null;
            }
        }

        private Path getJavadocArtifactPath(ArtifactDownloadReport adr, Collection all) {
            Artifact artifact = adr.getArtifact();
            _monitor.subTask("searching javadoc for " + artifact);
            for (Iterator iter = all.iterator(); iter.hasNext();) {
                ArtifactDownloadReport otherAdr = (ArtifactDownloadReport) iter.next();
                Artifact a = otherAdr.getArtifact();
                if (otherAdr.getLocalFile() != null
                        && isJavadocArtifactName(artifact.getName(), a.getName())
                        && a.getModuleRevisionId().equals(artifact.getModuleRevisionId())
                        && a.getId().equals(artifact.getId()) && isJavadoc(a)) {
                    return new Path(otherAdr.getLocalFile().getAbsolutePath());
                }
            }
            if (shouldTestNonDeclaredJavadocs()) {
                return getMetaArtifactPath(adr, "javadoc", "javadoc");
            } else {
                return null;
            }
        }

        /**
         * meta artifact (source or javadoc) not found in resolved artifacts, try to see if a non
         * declared one is available
         */
        private Path getMetaArtifactPath(ArtifactDownloadReport adr, String metaType,
                String metaClassifier) {
            Artifact artifact = adr.getArtifact();
            Map extraAtt = new HashMap(artifact.getExtraAttributes());
            extraAtt.put("classifier", metaClassifier);
            Artifact metaArtifact = new DefaultArtifact(artifact.getModuleRevisionId(), artifact
                    .getPublicationDate(), artifact.getName(), metaType, "jar", extraAtt);
            RepositoryCacheManager cache = _ivy.getSettings().getResolver(
                artifact.getModuleRevisionId()).getRepositoryCacheManager();
            if (cache instanceof DefaultRepositoryCacheManager) {
                File metaArtifactFile = ((DefaultRepositoryCacheManager) cache)
                        .getArchiveFileInCache(metaArtifact);
                File attempt = new File(metaArtifactFile.getAbsolutePath() + ".notfound");
                if (metaArtifactFile.exists()) {
                    return new Path(metaArtifactFile.getAbsolutePath());
                } else if (attempt.exists()) {
                    return null;
                } else {
                    Message.info("checking " + metaType + " for " + artifact);
                    _ivy.getResolveEngine().download(metaArtifact, new DownloadOptions());
                    if (metaArtifactFile.exists()) {
                        return new Path(metaArtifactFile.getAbsolutePath());
                    } else {
                        // meta artifact not found, we store this information to avoid other
                        // attempts later
                        Message.info(metaType + " not found for " + artifact);
                        try {
                            attempt.getParentFile().mkdirs();
                            attempt.createNewFile();
                        } catch (IOException e) {
                            Message
                                    .error("impossible to create attempt file " + attempt + ": "
                                            + e);
                        }
                        return null;
                    }
                }
            } else {
                Message.info("checking " + metaType + " for " + artifact);
                ArtifactDownloadReport metaAdr = _ivy.getResolveEngine().download(metaArtifact,
                    new DownloadOptions());
                if (metaAdr.getLocalFile() != null && metaAdr.getLocalFile().exists()) {
                    return new Path(metaAdr.getLocalFile().getAbsolutePath());
                } else {
                    Message.info(metaType + " not found for " + artifact);
                    Message
                            .verbose("Attempt not stored in cache because a non Default cache implementation is used.");
                    return null;
                }
            }
        }

        private IPath getSourceAttachment(Path classpathArtifact, Path sourcesArtifact) {
            IPath sourceAttachment = IvyPlugin.getDefault().getPackageFragmentExtraInfo()
                    .getSourceAttachment(classpathArtifact);
            if (sourceAttachment == null) {
                sourceAttachment = sourcesArtifact;
            }
            return sourceAttachment;
        }

        private IPath getSourceAttachmentRoot(Path classpathArtifact, Path sourcesArtifact) {
            IPath sourceAttachment = IvyPlugin.getDefault().getPackageFragmentExtraInfo()
                    .getSourceAttachmentRoot(classpathArtifact);
            if (sourceAttachment == null && sourcesArtifact != null) {
                sourceAttachment = sourcesArtifact;
            }
            return sourceAttachment;
        }

        private IClasspathAttribute[] getExtraAttribute(Path classpathArtifact, Path javadocArtifact) {
            List result = new ArrayList();
            URL url = IvyPlugin.getDefault().getPackageFragmentExtraInfo().getDocAttachment(
                classpathArtifact);

            if (url == null) {
                try {
                    Path path = javadocArtifact;
                    if (path != null) {
                        url = new URL("jar:" + path.toFile().toURI().toURL().toExternalForm()
                                + "!/");
                    }
                } catch (MalformedURLException e) {
                    // ignored
                }
            }

            if (url != null) {
                result.add(JavaCore.newClasspathAttribute(
                    IClasspathAttribute.JAVADOC_LOCATION_ATTRIBUTE_NAME, url.toExternalForm()));
            }
            return (IClasspathAttribute[]) result.toArray(new IClasspathAttribute[result.size()]);
        }

    }

    public static final String IVY_CLASSPATH_CONTAINER_ID = "org.apache.ivyde.eclipse.cpcontainer.IVYDE_CONTAINER";

    private IClasspathEntry[] _classpathEntries;

    private IJavaProject _javaProject;

    private File _ivyXmlFile;

    private IPath _path;

    private IvyResolveJob _job;

    IvyClasspathContainerConfiguration conf;

    private String jdtVersion;

    /**
     * Create an Ivy class path container from some predefined classpath entries. The provided class
     * path entries should come from the default "persisted" classpath container. Note that no
     * resolve nor resolve are exectued here, so some inconsistencies might exist between the
     * ivy.xml and the provided classpath entries.
     * 
     * @param javaProject
     *            the project of containing this container
     * @param path
     *            the path the container
     * @param ivyFile
     *            the path to the ivy file
     * @param confs
     *            the configuration that will be resolved
     * @param classpathEntries
     *            the entries to start with
     */
    public IvyClasspathContainer(IJavaProject javaProject, IPath path,
            IClasspathEntry[] classpathEntries) {
        this._javaProject = javaProject;
        _path = path;
        conf = new IvyClasspathContainerConfiguration(path);
        _ivyXmlFile = resolveFile(conf.ivyXmlPath);
        _classpathEntries = classpathEntries;
    }

    public IvyClasspathContainer(IvyClasspathContainer cp) {
        _javaProject = cp._javaProject;
        _path = cp._path;
        conf = cp.conf;
        _ivyXmlFile = cp._ivyXmlFile;
        _classpathEntries = cp._classpathEntries;
    }

    public IvyClasspathContainerConfiguration getConf() {
        return conf;
    }

    public IFile getIvyFile() {
        return _javaProject.getProject().getFile(conf.ivyXmlPath);
    }

    private File resolveFile(String path) {
        IFile iFile = _javaProject.getProject().getFile(path);
        return new File(iFile.getLocation().toOSString());
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.jdt.core.IClasspathContainer#getDescription()
     */
    public String getDescription() {
        return conf.ivyXmlPath + " " + conf.confs;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.jdt.core.IClasspathContainer#getKind()
     */
    public int getKind() {
        return K_APPLICATION;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.jdt.core.IClasspathContainer#getPath()
     */
    public IPath getPath() {
        return _path;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.jdt.core.IClasspathContainer#getClasspathEntries()
     */
    public IClasspathEntry[] getClasspathEntries() {
        return _classpathEntries;
    }

    private final static ISchedulingRule RESOLVE_EVENT_RULE = new ISchedulingRule() {
        public boolean contains(ISchedulingRule rule) {
            return rule == this;
        }

        public boolean isConflicting(ISchedulingRule rule) {
            return rule == this;
        }
    };

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.jdt.core.IClasspathContainer#getClasspathEntries()
     */
    private IvyResolveJob computeClasspathEntries(final boolean usePreviousResolveIfExist,
            boolean notify, boolean isUser) {
        try {
            // resolve job already running
            synchronized (this) {
                if (_job != null) {
                    return _job;
                }
                _job = new IvyResolveJob(usePreviousResolveIfExist, notify);
                _job.setUser(isUser);
                _job.setRule(RESOLVE_EVENT_RULE);
                return _job;
            }
        } catch (Exception e) {
            Message.error(e.getMessage());
            return null;
        }
    }

    /**
     * This method is here to available the Resolve all action to run in a single progress window.
     * It is quiet ugly but it is a first way to do this quiet quickly.
     * 
     * @param monitor
     */
    public void resolve(IProgressMonitor monitor) {
        computeClasspathEntries(false, true, true).run(monitor);
    }

    public void scheduleResolve() {
        computeClasspathEntries(false, true, true).schedule();
    }

    public void scheduleRefresh(boolean isUser) {
        computeClasspathEntries(true, true, isUser).schedule();
    }

    private void updateClasspathEntries(boolean notify, final IClasspathEntry[] classpathEntries) {
        IClasspathEntry[] entries;
        if (classpathEntries != null) {
            entries = classpathEntries;
        } else {
            entries = new IClasspathEntry[0];
        }
        setClasspathEntries(entries, notify);
    }

    private void setClasspathEntries(final IClasspathEntry[] entries, final boolean notify) {
        Display.getDefault().asyncExec(new Runnable() {
            public void run() {
                if (conf.isInheritedAlphaOrder()) {
                    Arrays.sort(entries, new Comparator() {
                        public int compare(Object o1, Object o2) {
                            return ((IClasspathEntry) o1).getPath().lastSegment().compareTo(
                                ((IClasspathEntry) o2).getPath().lastSegment());
                        }
                    });
                }
                _classpathEntries = entries;
                if (notify) {
                    notifyUpdateClasspathEntries();
                }
            }
        });
    }

    private void notifyUpdateClasspathEntries() {
        try {
            JavaCore.setClasspathContainer(_path, new IJavaProject[] {_javaProject},
                new IClasspathContainer[] {new IvyClasspathContainer(IvyClasspathContainer.this)},
                null);
        } catch (JavaModelException e) {
            Message.error(e.getMessage());
        }
    }

    public URL getReportUrl() {
        try {
            Ivy ivy = IvyPlugin.getIvy(_javaProject, conf.getInheritedIvySettingsPath());
            URL ivyURL = _ivyXmlFile.toURL();
            ModuleDescriptor md = ModuleDescriptorParserRegistry.getInstance().parseDescriptor(
                ivy.getSettings(), ivyURL, false);
            String resolveId = ResolveOptions.getDefaultResolveId(md);
            return ivy.getResolutionCacheManager().getConfigurationResolveReportInCache(resolveId,
                md.getConfigurationsNames()[0]).toURL();
        } catch (Exception ex) {
            return null;
        }
    }

    public IJavaProject getProject() {
        return _javaProject;
    }

    public boolean shouldTestNonDeclaredSources() {
        return true; // TODO: add settings for that
    }

    public boolean shouldTestNonDeclaredJavadocs() {
        return true; // TODO: add settings for that
    }

    public boolean isJavadocArtifactName(String jar, String javadoc) {
        return isArtifactName(jar, javadoc, conf.getInheritedJavadocSuffixes());
    }

    public boolean isSourceArtifactName(String jar, String source) {
        return isArtifactName(jar, source, conf.getInheritedSourceSuffixes());
    }

    private boolean isArtifactName(String jar, String name, Collection/* <String> */suffixes) {
        if (name.equals(jar)) {
            return true;
        }
        Iterator it = suffixes.iterator();
        while (it.hasNext()) {
            if (name.equals(jar + it.next())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if the artifact is an artifact which can be added to the classpath container
     * 
     * @param artifact
     *            the artifact to check
     * @return <code>true</code> if the artifact can be added
     */
    public boolean accept(Artifact artifact) {
        return conf.getInheritedAcceptedTypes().contains(artifact.getType())
                && !conf.getInheritedSourceTypes().contains(artifact.getType())
                && !conf.getInheritedJavadocTypes().contains(artifact.getType());
    }

    public boolean isSources(Artifact artifact) {
        return conf.getInheritedSourceTypes().contains(artifact.getType());
    }

    public boolean isJavadoc(Artifact artifact) {
        return conf.getInheritedJavadocTypes().contains(artifact.getType());
    }

}
