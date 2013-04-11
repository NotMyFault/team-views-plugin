/*
 * The MIT License
 *
 * Copyright 2013 Sony Mobile Communications AB. All rights reserved.
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
package com.sonymobile.jenkins.plugins.teamview;

import com.thoughtworks.xstream.annotations.XStreamAlias;
import hudson.BulkChange;
import hudson.CopyOnWrite;
import hudson.XmlFile;
import hudson.model.Descriptor;
import hudson.model.Saveable;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import hudson.model.listeners.SaveableListener;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;


/**
 * A team in Jenkins that can contain views.
 *
 * @author Tomas Westling &lt;tomas.westling@sonymobile.com&gt;
 */
@XStreamAlias("team")
public class Team implements Saveable {
    private static final Logger logger = Logger.getLogger(Team.class.getName());
    private static final String CONFIG_FILE_NAME = "config.xml";
    private static final String TEAM_DIRECTORY_NAME = "teams";

    private String name = "";
    private String description = "";

    /**
     * List of {@link TeamViewsProperty}s configured for this project.
     */
    @CopyOnWrite
    private volatile List<TeamProperty> properties = new ArrayList<TeamProperty>();


    static {
        Jenkins.XSTREAM.processAnnotations(Team.class);
        Jenkins.XSTREAM.processAnnotations(TeamViewsProperty.class);
    }

    /**
     * Standard constructor.
     *
     * @param name the name of the team.
     * @param description the description of this team.
     */
    public Team(String name, String description) {
        this.name = name;
        this.description = description;
        load();
    }

    /**
     * Loads the other data from disk if it's available.
     */
    public synchronized void load() {
        properties.clear();

        XmlFile config = getConfigFile();
        try {
            if (config.exists()) {
                config.unmarshal(this);
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to load " + config, e);
        }

        // remove nulls that have failed to load
        for (Iterator<TeamProperty> itr = properties.iterator(); itr.hasNext();) {
            if (itr.next() == null) {
                itr.remove();
            }
        }

        // allocate default instances if needed.
        // doing so after load makes sure that newly added user properties do get reflected
        for (TeamProperty.TeamPropertyDescriptor d : TeamProperty.all()) {
            if (getProperty(d.clazz) == null) {
                TeamProperty up = d.newInstance(this);
                if (up != null) {
                    properties.add(up);
                }
            }
        }
        for (TeamProperty p : properties) {
            p.setTeam(this);
        }
    }

    /**
     * Standard getter.
     *
     * @return the name.
     */
    public String getName() {
        return name;
    }

    /**
     * Standard getter.
     *
     * @return the description.
     */
    public String getDescription() {
        return description;
    }

    /**
     * Getter for the Url, relative to the Jenkins root to this team.
     * Will end in a '/'.
     *
     * @return the Url.
     */
    public String getUrl() {
        return Teams.TEAMS_URL_NAME + "/" + name + "/";
    }

    /**
     * Getter for the Url, relative to nearest ancestor, will not end in a '/'.
     *
     * @return the Url.
     */
    public String getUrlName() {
        return name;
    }

    /**
     * Getter for the list of properties.
     *
     * @return the properties.
     */
    public List<TeamProperty> getProperties() {
        return properties;
    }

    /**
     * Dynamic Stapler URL binding. Provides the ability to navigate to a team via for example:
     * <code>/jenkins/teams/team1</code>
     * @param token the team name.
     * @return a Team.
     */
    public Object getDynamic(String token) {
        for (TeamProperty property : getProperties()) {
            if (property.getUrlName().equals(token) || property.getUrlName().equals('/' + token)) {
                return property;
            }
        }
        return null;
    }

    /**
     * Updates the user object by adding a property.
     *
     * @param p the property to add.
     * @throws IOException if the Team cannot be saved.
     */
    public synchronized void addProperty(TeamViewsProperty p) throws IOException {
        TeamProperty old = getProperty(p.getClass());
        List<TeamProperty> ps = new ArrayList<TeamProperty>(properties);
        if (old != null) {
            ps.remove(old);
        }
        ps.add(p);
        p.setTeam(this);
        properties = ps;
        save();
    }

    /**
     * Gets the specific property, or null.
     *
     * @param clazz the Class to get the property for.
     * @return the property.
     */
    public TeamProperty getProperty(Class clazz) {
        for (TeamProperty p : properties) {
            if (clazz.isInstance(p)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Gets all the saved team names from disk.
     *
     * @return an Array of team names or null if no teams exist.
     */
    public static String[] getTeamNames() {
        File teamsDir = getRootDir();
        if (!teamsDir.exists() || !teamsDir.isDirectory()) {
            return null;
        }
        String[] list = teamsDir.list(new FilenameFilter() {
            @Override
            public boolean accept(File file, String s) {
                File file2 = new File(file, s);
                if (!file2.exists() || !file.isDirectory()) {
                    return false;
                }
                String[] list1 = file2.list(new FilenameFilter() {
                    @Override
                    public boolean accept(File file, String s) {
                        if (CONFIG_FILE_NAME.equals(s)) {
                            return true;
                        }
                        return false;
                    }
                });
                if (list1.length != 1) {
                    return false;
                }
                return true;
            }
        });
        return list;
    }

    /**
     * Gets the file in which we save our configuration.
     *
     * @return the XmlFile in which we save our configuration for the Team.
     */
    protected final XmlFile getConfigFile() {
        return new XmlFile(Jenkins.XSTREAM, getConfigFileFor(name));
    }

    /**
     * Gets the file in which we save our configuration for a specific Team.
     *
     * @param id the name of the specific Team
     * @return the XmlFile in which we save our configuration for the Team.
     */
    private static File getConfigFileFor(String id) {
        return new File(new File(getRootDir(), id), CONFIG_FILE_NAME);
    }

    /**
     * Gets the directory where Hudson stores user information.
     *
     * @return the directory.
     */
    private static File getRootDir() {
        return new File(Jenkins.getInstance().getRootDir(), TEAM_DIRECTORY_NAME);
    }

    /**
     * Renames the team on disk, used when we rename a Team.
     *
     * @param to the new team name.
     * @return true if the rename was successful, false if not.
     */
    private boolean renameTeamOnDisk(String to) {
        File teamDirectory = new File(getRootDir(), name);
        File newTeamDirectory = new File(getRootDir(), to);
        if (teamDirectory.exists() && teamDirectory.isDirectory()) {
            return teamDirectory.renameTo(newTeamDirectory);
        } else {
            return newTeamDirectory.mkdirs();
        }
    }

    /**
     * Save the settings to a file.
     *
     * @throws IOException if the file cannot be saved.
     */
    public synchronized void save() throws IOException {
        if (BulkChange.contains(this)) {
            return;
        }
        getConfigFile().write(this);
        SaveableListener.fireOnChange(this, getConfigFile());
    }

    /**
     * Run when the user saves a reconfigured team.
     *
     * @param request the StaplerRequest.
     * @param response the StaplerResponse.
     * @throws Exception if anything goes wrong with the form.
     */
    public synchronized void doConfigSubmit(StaplerRequest request, StaplerResponse response) throws Exception {
        JSONObject form = request.getSubmittedForm();
        String formName = form.getString("name");
        String formDescription = form.getString("description");
        String formPrimaryViewName = form.getString("primaryViewName");
        if (!formName.equals(name)) {
            if (PluginImpl.getInstance().getTeams().get(formName) == null) {
                boolean renamed = renameTeamOnDisk(formName);
                if (renamed) {
                    PluginImpl.getInstance().getTeams().remove(name);
                    name = formName;
                    PluginImpl.getInstance().addTeam(this);
                } else {
                    logger.warning("The team with name " + name + " could not be renamed");
                }
            } else {
                throw new Descriptor.FormException("A team with that name already exists!", "name");
            }
        }
        this.description = formDescription;
        for (TeamProperty prop : properties) {
            if (prop instanceof TeamViewsProperty) {
                ((TeamViewsProperty)prop).setPrimaryViewName(formPrimaryViewName);
            }
        }
        save();
        response.sendRedirect2("/" + getUrl());
    }
}
