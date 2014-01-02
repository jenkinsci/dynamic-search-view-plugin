/*
 * The MIT License
 *
 * Copyright 2013 Oleg Nenashev <nenashev@synopsys.com>, Synopsys Inc.
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

import hudson.Extension;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.model.ListView;
import hudson.model.TopLevelItem;
import hudson.model.View;
import hudson.model.ViewDescriptor;
import hudson.search.Search;
import hudson.util.DescribableList;
import hudson.util.FormValidation;
import hudson.views.ViewJobFilter;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.servlet.ServletException;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

/**
 * List View with dynamic filters.
 * Class uses internal storage to pass parameters between pages.
 * @todo Add support of URLs
 * @todo Add "Save as view" button
 * @fixme Add garbage collector
 * @author Oleg Nenashev <nenashev@synopsys.com>, Synopsys Inc.
 */
public class SimpleSearchView extends ListView {

    transient UserContextCache contextMap;
    
    private String defaultIncludeRegex;
    private DescribableList<ViewJobFilter, Descriptor<ViewJobFilter>> defaultJobFilters;

    @DataBoundConstructor
    public SimpleSearchView(String name) {
        super(name);
    } 

    public String getDefaultIncludeRegex() {
        return defaultIncludeRegex;
    }

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
        defaultIncludeRegex = hudson.Util.fixEmpty(req.getParameter("defaultIncludeRegex").toString());
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
        return contextMap!=null && contextMap.containsKey(getSessionId());
    }
    
    public JobsFilter getFilters() {
        return hasConfiguredFilters() 
                ? contextMap.get(getSessionId()).getFiltersConfig()
                : getDefaultFilters();
    }

    /**
     * Gets default search options for UI. 
     * @since 0.2
     */
    public JobsFilter getDefaultFilters() {
        return new JobsFilter(this, 
                    defaultJobFilters.getAll(ViewJobFilter.class), 
                    defaultIncludeRegex, null);
    }
    
    /**
     * An override for future versions.
     */
    public boolean isAutomaticRefreshEnabled() {
        return false;
    }
       
    /**
     * Checks that the auto-refresh is enabled for the page.
     */
    public boolean isAutoRefreshActive() {
        return true;
        //TODO: implement something cool
       // Stapler.getCurrentResponse().get
    }
    
    /**
     * Cleans internal cache of JSON Objects for the session.
     * @todo Cleanup approach, replace for URL-based parameterization
     * @return sessionId
     */
    public String cleanCache() {
        final String sessionId = getSessionId();
        if (hasConfiguredFilters()) {
            contextMap.flush(sessionId);
        }
        
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
       
    public void doSearchSubmit(StaplerRequest req, StaplerResponse rsp) 
            throws IOException, UnsupportedEncodingException, ServletException, 
            Descriptor.FormException {
        Hudson.getInstance().checkPermission(View.READ);
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
            default:
                throw new IOException("Action "+action+" is not supported");
        } 
     }
    
    public void updateSearchCache(JobsFilter filter) {
        // Put Context to the map
        if (contextMap==null) {
            synchronized(this) {
                contextMap = new UserContextCache();
            }
        }
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
        
        /**
         * Checks if the include regular expression is valid.
         */
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
        resetDefaultsButton;
        
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
