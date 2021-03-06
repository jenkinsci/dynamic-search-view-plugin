/*
 * The MIT License
 *
 * Copyright 2013-2015 Oleg Nenashev, Synopsys Inc.
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
package com.synopsys.arc.jenkinsci.plugins.dynamic_search.views;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.RestrictedSince;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.ListView;
import hudson.model.TopLevelItem;
import hudson.model.View;
import hudson.model.ViewDescriptor;
import hudson.search.Search;
import hudson.util.DescribableList;
import hudson.util.FormValidation;
import hudson.util.VersionNumber;
import hudson.views.ViewJobFilter;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

// TODO: Add support of URLs
// TODO: Add "Save as view" button
// TODO: Add garbage collector

/**
 * List View with dynamic filters.
 * The class is being displayed as a &quot;Dynamic Search View&quot; in Jenkins UI,
 * but we keep the original class name in order to maintain the backward compatibility.
 * Class uses internal storage to pass parameters between pages.
 * @author Oleg Nenashev
 */
public class SimpleSearchView extends ListView {

    /**
     * A minimal version, where views can disable the automatic refresh.
     * @since 0.2.1
     */
    public static final VersionNumber MINIMAL_AUTOREFRESH_VERSION = new VersionNumber("1.557");
    
    @Nonnull
    transient UserContextCache contextMap;
    
    @CheckForNull
    private String defaultIncludeRegex;
    @CheckForNull
    private DescribableList<ViewJobFilter, Descriptor<ViewJobFilter>> defaultJobFilters;

    @DataBoundConstructor
    public SimpleSearchView(String name) {
        super(name);
        this.contextMap = new UserContextCache();
    } 

    @SuppressFBWarnings(value = "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE", justification = "recovers the transient field")
    protected Object readResolve() {
        if (contextMap == null) {
            contextMap = new UserContextCache();
        }
        return this;
    }

    @CheckForNull
    public String getDefaultIncludeRegex() {
        return defaultIncludeRegex;
    }

    @CheckForNull
    public DescribableList<ViewJobFilter, Descriptor<ViewJobFilter>> getDefaultJobFilters() {
        return defaultJobFilters;
    }

    @Override
    protected void submit(StaplerRequest req) throws ServletException, Descriptor.FormException, IOException {
        super.submit(req); 
        
        // Handle default UI settings
        if (defaultJobFilters == null) {
            defaultJobFilters = new DescribableList<ViewJobFilter, Descriptor<ViewJobFilter>>(this);
        }
        defaultJobFilters.rebuildHetero(req, req.getSubmittedForm(), ViewJobFilter.all(), "defaultJobFilters");
        defaultIncludeRegex = hudson.Util.fixEmpty(req.getParameter("defaultIncludeRegex"));
    }
         
    /**
     * Gets identifier of the current session.
     * @return Unique id of the current session.
     */
    public static String getSessionId() {
        return Stapler.getCurrentRequest().getSession().getId(); 
    }
    
    @Override
    public Search getSearch() {
        return super.getSearch(); 
    }  

    public boolean hasConfiguredFilters() {
        return contextMap.containsKey(getSessionId());
    }
    
    /**
     * Retrieves view filters for the user.
     * @return View filters from the session if it is available. 
     * Otherwise, default filters will be returned.
     */
    public JobsFilter getFilters() {
        UserContext context = contextMap.get(getSessionId());
        return context != null ? context.getFiltersConfig() : getDefaultFilters();
    }

    /**
     * Gets default search options for UI. 
     * @return a default set of filters.
     * @since 0.2
     */
    @Nonnull
    public JobsFilter getDefaultFilters() {
        return new JobsFilter(this, defaultJobFilters != null ? defaultJobFilters.toList() : null, 
                defaultIncludeRegex, null);
    }
    
    /**
     * An override for future versions (since 1.557).
     * @return Always false
     */
    public boolean isAutomaticRefreshEnabled() {
        return false;
    }
    
    @Restricted(NoExternalUse.class)
    public void setDefaultIncludeRegex(String regex) {
        this.defaultIncludeRegex = regex;
    }

    //TODO: Cleanup approach, replace for URL-based parameterization
    /**
     * Cleans internal cache of JSON Objects for the session.
     * @return Current Session Id
     */
    public String cleanCache() {
        final String sessionId = getSessionId();
        contextMap.flush(sessionId);
        
        //TODO: garbage collector       
        return sessionId;
    }
    
    @Override
    public List<TopLevelItem> getItems() {
        // Handle filters from config
        List<TopLevelItem> res = super.getItems(); 
        
        // Handle user-specified filters
        JobsFilter filters = getFilters();
        return filters.doFilter(res, this);
    }

    @Restricted(NoExternalUse.class)
    @RestrictedSince("0.3.0")
    public void doSearchSubmit(StaplerRequest req, StaplerResponse rsp) 
            throws IOException, UnsupportedEncodingException, ServletException, 
            Descriptor.FormException {
        final Jenkins jenkins = Jenkins.getInstance();
        if (jenkins == null) {
            throw new IOException("Jenkins instance is not ready");
        }
        jenkins.checkPermission(View.READ);
        SearchAction action = SearchAction.fromRequest(req);
        
        switch (action) {
            case runSearchButton:
                JobsFilter filter = new JobsFilter(req, this);
                updateSearchCache(filter);
                rsp.sendRedirect(".");
                break;
            case resetDefaultsButton:
                updateSearchCache(getDefaultFilters());
                rsp.sendRedirect(".");
                break;
            //TODO: Implement "Save As"
            default:
                throw new IOException("Action "+action+" is not supported");
        } 
     }
    
    public void updateSearchCache(JobsFilter filter) {
        // Put Context to the map
        contextMap.put(getSessionId(), new UserContext(filter));
    }
      
    @Extension
    public static final class DescriptorImpl extends ViewDescriptor {
        
        @Override
        public String getDisplayName() {
            return Messages.SimpleSearchView_displayName();
        }      
        
        public FormValidation doCheckDefaultIncludeRegex ( @QueryParameter String value ) 
                throws IOException, ServletException, InterruptedException  {
            return doCheckIncludeRegex(value);
        }

        @Restricted(NoExternalUse.class)
        @RestrictedSince("0.3.0")
        public FormValidation doCheckIncludeRegex( @QueryParameter String value ) throws IOException, ServletException, InterruptedException  {
            String v = Util.fixEmpty(value);
            if (v != null) {
                try {
                    Pattern.compile(v);
                } catch (PatternSyntaxException pse) {
                    return FormValidation.error(pse.getMessage());
                }
            }
            return FormValidation.ok();
        }   
      
        /**
         * Checks that the auto-refresh may be enabled for the page.
         * @return true if the Jenkins core does not support the auto-refresh
         * disabling.
         */
        public boolean isAutoRefreshActive() {
            VersionNumber version = Jenkins.getVersion();
            if (version == null) {
                return false; // It is about to be removed in 2.1xx at all, https://github.com/jenkinsci/jenkins/pull/2979
            }
            return version.isOlderThan(MINIMAL_AUTOREFRESH_VERSION);
        }
    }
  
    public boolean hasUserJobFilterExtensions() {
        return !ViewJobFilter.all().isEmpty();
    }
    
    /**
     * Defines actions inside Search panel.
     * @since 0.2
     */
    enum SearchAction {   
        runSearchButton,
        resetDefaultsButton,
        saveSearch;
        
        static SearchAction fromRequest(StaplerRequest req) throws IOException {
            Map map = req.getParameterMap();
            for (SearchAction val : SearchAction.values()) {
                if (map.containsKey(val.toString())) {
                    return val;
                }
            }
            throw new IOException("Cannot find an action in the reqest");
        }
    }
}
