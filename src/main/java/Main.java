import java.awt.*;
import java.awt.event.ActionListener;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;

public class Main {
  private static final Logger LOGGER = Logger.getLogger(Main.class.getName());

  private static void logWarning(Exception e) {
    StringBuilder warning = new StringBuilder();
    warning.append(e.getMessage());
    StackTraceElement[] stackTrace = e.getStackTrace();
    for (StackTraceElement element : stackTrace) {
      warning.append(element.toString()).append(System.lineSeparator());
    }
    LOGGER.warning(warning.toString());

    JOptionPane.showMessageDialog(null, e.getMessage());
  }

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
        logWarning(e);
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
        logWarning(e);
        close(null);
      }
    }

    public void close(Session session) {
      if (session != null) {
        try {
          session.close();
        } catch (IOException e) {
          logWarning(e);
        }
      }
      if (ssh != null) {
        try {
          ssh.disconnect();
        } catch (IOException e) {
          logWarning(e);
        }
      }
    }

    public Session getSession() {
      try {
        return ssh.startSession();
      } catch (IOException e) {
        logWarning(e);
        close(null);
      }
      return null;
    }
  }

  public static void main(String[] args) {
    SwingUtilities.invokeLater(Main::showGUI);
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
      logWarning(e);
    } finally {
      connection.close(session);
    }
    return result;
  }

  private static class MyActionListener implements ActionListener {
    private final JFrame frame;
    private final JTable table;
    private final DefaultTableModel model;
    private final String command;

    private MyActionListener(JFrame frame, JTable table, DefaultTableModel model, String command) {
      this.frame = frame;
      this.table = table;
      this.model = model;
      this.command = command;
    }

    @Override
    public void actionPerformed(java.awt.event.ActionEvent e) {
      enableGUI(frame, false);
      int row = table.getSelectedRow();
      if (row != -1) {
        String containerId = (String) model.getValueAt(table.convertRowIndexToModel(row), 0);
        MyConnection connection = new MyConnection();
        Session session = connection.getSession();
        try {
          Session.Command cmd = session.exec(command + " " + containerId);
          try (BufferedReader reader =
              new BufferedReader(
                  new InputStreamReader(cmd.getInputStream(), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (line != null) {
              JOptionPane.showMessageDialog(frame, line);
            }
          }
        } catch (IOException ex) {
          logWarning(ex);
        } finally {
          connection.close(session);
        }
      }
      enableGUI(frame, true);
    }
  }

  private static void showGUI() {
    JButton button1 = new JButton("Refresh");
    JButton button2 = new JButton("Stop");
    JButton button3 = new JButton("Start");
    JButton button4 = new JButton("Restart");
    button2.setEnabled(false);
    button3.setEnabled(false);
    button4.setEnabled(false);
    JPanel panel1 = new JPanel(new GridLayout(1, 4));
    panel1.add(button1);
    panel1.add(button2);
    panel1.add(button3);
    panel1.add(button4);
    JPanel panel2 = new JPanel(new BorderLayout());
    panel2.add(panel1, BorderLayout.WEST);
    String[] columnNames = {
      "CONTAINER ID", "NAME", "CPU %", "MEM USAGE", "LIMIT", "MEM %", "NET I/O", "BLOCK I/O", "PIDS"
    };
    DefaultTableModel model = new DefaultTableModel(columnNames, 0);
    JTable table = new JTable(model);
    table.setAutoCreateRowSorter(true);
    table
        .getSelectionModel()
        .addListSelectionListener(
            event -> {
              if (event.getValueIsAdjusting()) {
                return;
              }
              if (table.getSelectedRow() == -1) {
                button2.setEnabled(false);
                button3.setEnabled(false);
                button4.setEnabled(false);
              } else {
                button2.setEnabled(true);
                button3.setEnabled(true);
                button4.setEnabled(true);
              }
            });
    JScrollPane scrollPane = new JScrollPane(table);
    JFrame frame = new JFrame("Docker Stats");
    frame.setLayout(new BorderLayout());
    frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    frame.setSize(1600, 800);
    frame.add(panel2, BorderLayout.NORTH);
    frame.add(scrollPane, BorderLayout.CENTER);
    frame.setVisible(true);

    button1.addActionListener(
        e -> {
          enableGUI(frame, false);
          String[] tableArray = getTableArray();
          model.setRowCount(0);
          for (String line : tableArray) {
            if (line.startsWith("CONTAINER ID")) {
              continue;
            }
            String[] rowData = line.split("\\s{2,}");
            String[] rowData2 = new String[rowData.length + 1];
            String[] temp = rowData[3].split(" / ");
            System.arraycopy(rowData, 0, rowData2, 0, 3);
            System.arraycopy(temp, 0, rowData2, 3, 2);
            System.arraycopy(rowData, 4, rowData2, 5, rowData.length - 4);
            model.addRow(rowData2);
          }
          model.fireTableDataChanged();
          enableGUI(frame, true);
        });
    button2.addActionListener(new MyActionListener(frame, table, model, "docker stop"));
    button3.addActionListener(new MyActionListener(frame, table, model, "docker start"));
    button4.addActionListener(new MyActionListener(frame, table, model, "docker restart"));
  }

  private static void enableGUI(JFrame frame, boolean enable) {
    for (Component component : frame.getContentPane().getComponents()) {
      component.setEnabled(enable);
    }
  }
}
