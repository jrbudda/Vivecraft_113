package org.vivecraft.settings.profile;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

import org.json.JSONObject;

/**
 * Created by StellaArtois on 13/04/15.
 */
public class ProfileReader
{
    private String set;
    private String profile;
    private Map<String, String> currentProfile = null;
    private Iterator profileSettingsIt = null;
    private JSONObject theProfiles = null;

    public ProfileReader(String set)
    {
        this.profile = ProfileManager.currentProfileName;
        this.set = set;
    }

    public ProfileReader(String set, JSONObject theProfiles)
    {
        this.profile = ProfileManager.currentProfileName;
        this.set = set;
        this.theProfiles = theProfiles;
    }

    public String readLine() throws IOException {

        String line = null;

        // Return next line from current profile only...
        if (this.currentProfile == null) {
            if (this.theProfiles == null) {
                this.currentProfile = ProfileManager.getProfileSet(this.profile, this.set);
            }
            else {
                this.currentProfile = ProfileManager.getProfileSet(this.theProfiles, this.set);
            }
            this.profileSettingsIt = this.currentProfile.entrySet().iterator();
        }

        if (this.profileSettingsIt.hasNext()) {
            Map.Entry thisEntry = (Map.Entry) this.profileSettingsIt.next();
            String setting = (String)thisEntry.getKey();
            String value = (String)thisEntry.getValue();
            if (value == null) {
                value = "";
            }
            line = setting + ":" + value;
        }

        return line;
    }

    public void close()
    {

    }

    public Map<String, String> getData() {
        return currentProfile;
    }
}
