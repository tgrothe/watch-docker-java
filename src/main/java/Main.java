import java.io.*;
import java.nio.charset.StandardCharsets;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;

public class Main {
  private static final String HOST;
  private static final int PORT;
  private static final String USERNAME;

  static {
    File hostData = new File("my_host_data.txt");
    if (hostData.exists()) {
      String[] lines = new String[3];
      try (BufferedReader reader =
          new BufferedReader(new FileReader(hostData, StandardCharsets.UTF_8))) {
        lines = reader.lines().toArray(String[]::new);
      } catch (IOException e) {
        e.printStackTrace();
      }
      HOST = lines[0];
      PORT = Integer.parseInt(lines[1]);
      USERNAME = lines[2];
    } else {
      HOST = "localhost";
      PORT = 22;
      USERNAME = "my_username";
    }
  }

  public static class MyConnection {
    private final SSHClient ssh;

    public MyConnection() {
      ssh = new SSHClient();
      try {
        ssh.loadKnownHosts();
        ssh.connect(HOST, PORT);
        ssh.authPublickey(USERNAME);
      } catch (IOException e) {
        e.printStackTrace();
        close(null);
      }
    }

    public void close(Session session) {
      if (session != null) {
        try {
          session.close();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
      if (ssh != null) {
        try {
          ssh.disconnect();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }

    public Session getSession() {
      try {
        return ssh.startSession();
      } catch (IOException e) {
        e.printStackTrace();
        close(null);
      }
      return null;
    }
  }

  public static void main(String[] args) {
    String[] tableArray = getTableArray();
    for (String line : tableArray) {
      System.out.println(line);
    }
  }

  private static String[] getTableArray() {
    String[] result = new String[0];
    MyConnection connection = new MyConnection();
    Session session = connection.getSession();
    try {
      Session.Command cmd = session.exec("docker stats --no-stream | sort -k 2");
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(cmd.getInputStream(), StandardCharsets.UTF_8))) {
        result = reader.lines().toArray(String[]::new);
      }
    } catch (IOException e) {
      e.printStackTrace();
    } finally {
      connection.close(session);
    }
    return result;
  }
}
