import com.formdev.flatlaf.FlatDarkLaf;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.connection.channel.direct.Signal;

public class Main {
  private static final Logger LOGGER = Logger.getLogger(Main.class.getName());

  private static void logWarning(Exception e) {
    StringBuilder warning = new StringBuilder();
    warning.append(e.getMessage()).append(System.lineSeparator());
    StackTraceElement[] stackTrace = e.getStackTrace();
    for (StackTraceElement element : stackTrace) {
      warning.append(element.toString()).append(System.lineSeparator());
    }
    LOGGER.warning(warning.toString());

    JOptionPane.showMessageDialog(null, e.getMessage());
  }

  private static final String versionNumber = "v0.9";

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

  private static class MyConnection {
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
      Session.Command cmd = session.exec("docker stats -a --no-stream | sort -k 2");
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

  private record ButtonGroup(
      JButton button1, JButton button2, JButton button3, JButton button4, JButton button5) {
    public void setEnabled(boolean enabled) {
      button2.setEnabled(enabled);
      button3.setEnabled(enabled);
      button4.setEnabled(enabled);
      button5.setEnabled(enabled);
    }
  }

  private record CommandActionListener(JFrame frame, JTable table, String command)
      implements ActionListener {
    @Override
    public void actionPerformed(ActionEvent e) {
      int row = table.getSelectedRow();
      if (row != -1) {
        String containerId = (String) table.getValueAt(row, table.convertColumnIndexToView(0));
        MyConnection connection = new MyConnection();
        Session session = connection.getSession();
        try {
          Session.Command cmd = session.exec(command + " " + containerId);
          try (BufferedReader reader =
              new BufferedReader(
                  new InputStreamReader(cmd.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
              output.append(line).append(System.lineSeparator());
            }
            JOptionPane.showMessageDialog(frame, output.toString());
          }
        } catch (IOException ex) {
          logWarning(ex);
        } finally {
          connection.close(session);
        }
      }
    }
  }

  private record LogActionListener(JTable table) implements ActionListener {
    @Override
    public void actionPerformed(ActionEvent e) {
      int row = table.getSelectedRow();
      if (row != -1) {
        String containerId = (String) table.getValueAt(row, table.convertColumnIndexToView(0));
        String isRunning = (String) table.getValueAt(row, table.convertColumnIndexToView(11));

        JFrame logsFrame = new JFrame("Logs " + containerId);
        JTextArea textArea = new JTextArea();
        textArea.setEditable(false);
        logsFrame.add(new JScrollPane(textArea));
        logsFrame.setSize(1000, 600);
        logsFrame.setLocationRelativeTo(null);
        logsFrame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        logsFrame.setVisible(true);

        new Thread(
                () -> {
                  MyConnection connection = new MyConnection();
                  Session session = connection.getSession();
                  try {
                    Session.Command cmd =
                        session.exec("docker logs -tf --tail 1000 " + containerId);
                    logsFrame.addWindowListener(
                        new java.awt.event.WindowAdapter() {
                          @Override
                          public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                            if (cmd.isOpen()) {
                              try {
                                cmd.signal(Signal.INT);
                              } catch (IOException ex) {
                                logWarning(ex);
                              }
                            }
                          }
                        });

                    try (BufferedReader reader =
                        new BufferedReader(
                            new InputStreamReader(cmd.getInputStream(), StandardCharsets.UTF_8))) {
                      String line;
                      while ((line = reader.readLine()) != null) {
                        textArea.append(line + "\n");
                        textArea.setCaretPosition(textArea.getDocument().getLength());
                      }
                    }

                    if ("Yes".equals(isRunning)) {
                      // The container was running before listener call and now stopped
                      logsFrame.dispose();
                    }
                  } catch (IOException ex) {
                    logWarning(ex);
                  } finally {
                    connection.close(session);
                  }
                })
            .start();
      }
    }
  }

  private interface ModelValue extends Comparable<Object> {
    void setValue(String value);
  }

  private static class ModelValueString implements ModelValue {
    private String value = "";

    @Override
    public void setValue(String value) {
      this.value = value;
    }

    @Override
    public int compareTo(Object o) {
      return value.compareTo(((ModelValueString) o).value);
    }

    @Override
    public boolean equals(Object o) {
      if (o == null || getClass() != o.getClass()) return false;

      ModelValueString that = (ModelValueString) o;
      return value.equals(that.value);
    }

    @Override
    public int hashCode() {
      return value.hashCode();
    }

    @Override
    public String toString() {
      return value;
    }
  }

  private static class ModelValuePercent implements ModelValue {
    private String value;
    private double doubleValue;

    @Override
    public void setValue(String value) {
      this.value = value;
      doubleValue = Double.parseDouble(value.substring(0, value.length() - 1));
    }

    @Override
    public int compareTo(Object o) {
      return Double.compare(doubleValue, ((ModelValuePercent) o).doubleValue);
    }

    @Override
    public boolean equals(Object o) {
      if (o == null || getClass() != o.getClass()) return false;

      ModelValuePercent that = (ModelValuePercent) o;
      return Double.compare(doubleValue, that.doubleValue) == 0;
    }

    @Override
    public int hashCode() {
      return Double.hashCode(doubleValue);
    }

    @Override
    public String toString() {
      return value;
    }
  }

  private static class ModelValueByte implements ModelValue {
    private String value;
    private long longValue;

    @Override
    public void setValue(String value) {
      this.value = value;
      Object[][] temp = {
        {"KiB", 1024L},
        {"MiB", 1024L * 1024L},
        {"GiB", 1024L * 1024L * 1024L},
        {"kB", 1000L},
        {"MB", 1000L * 1000L},
        {"GB", 1000L * 1000L * 1000L},
        {"B", 1L},
        {"", 1L}
      };
      for (Object[] os : temp) {
        if (value.endsWith((String) os[0])) {
          longValue =
              (long)
                  (Double.parseDouble(
                          value.substring(0, value.length() - ((String) os[0]).length()))
                      * (Long) os[1]);
          break;
        }
      }
    }

    @Override
    public int compareTo(Object o) {
      return Long.compare(longValue, ((ModelValueByte) o).longValue);
    }

    @Override
    public boolean equals(Object o) {
      if (o == null || getClass() != o.getClass()) return false;

      ModelValueByte that = (ModelValueByte) o;
      return longValue == that.longValue;
    }

    @Override
    public int hashCode() {
      return Long.hashCode(longValue);
    }

    @Override
    public String toString() {
      return value;
    }
  }

  private static class ModelRow {
    private final ModelValueString containerId = new ModelValueString();
    private final ModelValueString name = new ModelValueString();
    private final ModelValuePercent cpu = new ModelValuePercent();
    private final ModelValueByte memUsage = new ModelValueByte();
    private final ModelValueByte limit = new ModelValueByte();
    private final ModelValuePercent mem = new ModelValuePercent();
    private final ModelValueByte netI = new ModelValueByte();
    private final ModelValueByte netO = new ModelValueByte();
    private final ModelValueByte blockI = new ModelValueByte();
    private final ModelValueByte blockO = new ModelValueByte();
    private final ModelValueByte pid = new ModelValueByte();
    private final ModelValueString run = new ModelValueString();

    public ModelRow(String line) {
      setValues(line);
    }

    private void setValues(String line) {
      String[] rowData = line.split("\\s{2,}");
      String[] temp1 = rowData[3].split(" / ");
      String[] temp2 = rowData[5].split(" / ");
      String[] temp3 = rowData[6].split(" / ");
      containerId.setValue(rowData[0]);
      name.setValue(rowData[1]);
      cpu.setValue(rowData[2]);
      memUsage.setValue(temp1[0]);
      limit.setValue(temp1[1]);
      mem.setValue(rowData[4]);
      netI.setValue(temp2[0]);
      netO.setValue(temp2[1]);
      blockI.setValue(temp3[0]);
      blockO.setValue(temp3[1]);
      pid.setValue(rowData[7]);
      run.setValue("0".equals(rowData[7]) ? "No" : "Yes");
    }

    public Object getValueAt(int columnIndex) {
      return switch (columnIndex) {
        case 0 -> containerId;
        case 1 -> name;
        case 2 -> cpu;
        case 3 -> memUsage;
        case 4 -> limit;
        case 5 -> mem;
        case 6 -> netI;
        case 7 -> netO;
        case 8 -> blockI;
        case 9 -> blockO;
        case 10 -> pid;
        case 11 -> run;
        default -> throw new IllegalArgumentException("Unexpected column value: " + columnIndex);
      };
    }
  }

  private static class MyTableModel extends AbstractTableModel {
    private final String[] columnNames = {
      "CONTAINER ID",
      "NAME",
      "CPU %",
      "MEM USAGE",
      "LIMIT",
      "MEM %",
      "NET I",
      "NET O",
      "BLOCK I",
      "BLOCK O",
      "PIDS",
      "RUNS"
    };

    private final ArrayList<ModelRow> data = new ArrayList<>();

    public void update() {
      data.clear();
      String[] tableArray = getTableArray();
      for (String line : tableArray) {
        addRow(line);
      }
      fireTableDataChanged();
    }

    private void addRow(String line) {
      if (line.startsWith("CONTAINER ID")) {
        return;
      }
      data.add(new ModelRow(line));
    }

    @Override
    public int getRowCount() {
      return data.size();
    }

    @Override
    public int getColumnCount() {
      return columnNames.length;
    }

    @Override
    public String getColumnName(int column) {
      return columnNames[column];
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
      return data.get(rowIndex).getValueAt(columnIndex);
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
      return ModelValue.class;
    }
  }

  private static void showGUI() {
    FlatDarkLaf.installLafInfo();
    for (UIManager.LookAndFeelInfo laf : UIManager.getInstalledLookAndFeels()) {
      if ("FlatLaf Dark".equals(laf.getName())) {
        try {
          UIManager.setLookAndFeel(laf.getClassName());
        } catch (Exception e) {
          logWarning(e);
        }
        break;
      }
    }

    ButtonGroup buttonGroup =
        new ButtonGroup(
            new JButton("Refresh"),
            new JButton("Stop"),
            new JButton("Start"),
            new JButton("Restart"),
            new JButton("Show Logs"));
    buttonGroup.setEnabled(false);
    JPanel panel1 = new JPanel(new GridLayout(1, 5));
    panel1.add(buttonGroup.button1());
    panel1.add(buttonGroup.button2());
    panel1.add(buttonGroup.button3());
    panel1.add(buttonGroup.button4());
    panel1.add(buttonGroup.button5());
    JPanel panel2 = new JPanel(new BorderLayout());
    panel2.add(panel1, BorderLayout.WEST);
    MyTableModel model = new MyTableModel();
    JTable table = new JTable(model);
    table.setAutoCreateRowSorter(true);
    table
        .getSelectionModel()
        .addListSelectionListener(
            event -> {
              if (event.getValueIsAdjusting()) {
                return;
              }
              buttonGroup.setEnabled(table.getSelectedRow() != -1);
            });
    JScrollPane scrollPane = new JScrollPane(table);
    JFrame frame = new JFrame("Docker Stats " + versionNumber);
    frame.setLayout(new BorderLayout());
    frame.add(panel2, BorderLayout.NORTH);
    frame.add(scrollPane, BorderLayout.CENTER);
    frame.setSize(1200, 600);
    frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    frame.setVisible(true);

    buttonGroup.button1().addActionListener(e -> model.update());
    buttonGroup.button2().addActionListener(new CommandActionListener(frame, table, "docker stop"));
    buttonGroup
        .button3()
        .addActionListener(new CommandActionListener(frame, table, "docker start"));
    buttonGroup
        .button4()
        .addActionListener(new CommandActionListener(frame, table, "docker restart"));
    buttonGroup.button5().addActionListener(new LogActionListener(table));

    TableColumn nameColumn = table.getColumnModel().getColumn(table.convertColumnIndexToView(1));
    nameColumn.setPreferredWidth((int) (nameColumn.getPreferredWidth() * 1.6));
  }
}
