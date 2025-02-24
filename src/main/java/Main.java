import java.awt.*;
import java.awt.event.ActionListener;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
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

  private record MyActionListener(JFrame frame, JTable table, MyTableModel model, String command)
      implements ActionListener {
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

  public static class MyTableModel extends AbstractTableModel {
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

    private final ArrayList<ArrayList<Object>> data = new ArrayList<>();

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
      String[] rowData = line.split("\\s{2,}");
      String[] temp1 = rowData[3].split(" / ");
      String[] temp2 = rowData[5].split(" / ");
      String[] temp3 = rowData[6].split(" / ");
      ArrayList<Object> row = new ArrayList<>();
      row.add(rowData[0]);
      row.add(rowData[1]);
      row.add(rowData[2]);
      row.add(temp1[0]);
      row.add(temp1[1]);
      row.add(rowData[4]);
      row.add(temp2[0]);
      row.add(temp2[1]);
      row.add(temp3[0]);
      row.add(temp3[1]);
      row.add(rowData[7]);
      row.add("0".equals(rowData[7]) ? "No" : "Yes");
      data.add(row);
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
      return data.get(rowIndex).get(columnIndex);
    }

    public TableRowSorter<MyTableModel> getSorter() {
      TableRowSorter<MyTableModel> sorter = new TableRowSorter<>(this);
      TreeMap<String, Long> map =
          new TreeMap<>(
              (s1, s2) -> {
                int l1 = s1.length();
                int l2 = s2.length();
                if (l1 != l2) {
                  return Integer.compare(l2, l1);
                }
                return s1.compareTo(s2);
              });
      map.put("%", 1L);
      map.put("B", 1L);
      map.put("KiB", 1024L);
      map.put("MiB", 1024L * 1024L);
      map.put("GiB", 1024L * 1024L * 1024L);
      map.put("kB", 1000L);
      map.put("MB", 1000L * 1000L);
      map.put("GB", 1000L * 1000L * 1000L);
      Comparator<String> comparator =
          (o1, o2) -> {
            Double d1 = null;
            for (Map.Entry<String, Long> entry : map.entrySet()) {
              if (o1.endsWith(entry.getKey())) {
                d1 =
                    Double.parseDouble(o1.substring(0, o1.length() - entry.getKey().length()))
                        * entry.getValue();
                break;
              }
            }
            Double d2 = null;
            for (Map.Entry<String, Long> entry : map.entrySet()) {
              if (o2.endsWith(entry.getKey())) {
                d2 =
                    Double.parseDouble(o2.substring(0, o2.length() - entry.getKey().length()))
                        * entry.getValue();
                break;
              }
            }
            if (d1 == null || d2 == null) {
              return o1.compareTo(o2);
            }
            return Double.compare(d1, d2);
          };
      for (int i = 0; i < columnNames.length; i++) {
        sorter.setComparator(i, comparator);
      }
      return sorter;
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
    MyTableModel model = new MyTableModel();
    JTable table = new JTable(model);
    table.setRowSorter(model.getSorter());
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
          model.update();
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
