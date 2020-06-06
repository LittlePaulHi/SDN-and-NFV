package nctu.winlab.unicastdhcp;

import org.onosproject.core.ApplicationId;
import org.onosproject.net.config.Config;

public class UnicastDhcpConfig extends Config<ApplicationId> {

    private final String field = "serverLocation";
  
    @Override
    public boolean isValid() {
      return hasOnlyFields(field);
    }
  
    public String GetSwitchDevice() {
        return get(field, null).split("/")[0];
    }

    public String GetPort() {
        return get(field, null).split("/")[1];
    }

}