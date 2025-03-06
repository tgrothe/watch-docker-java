import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.logging.Logger;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
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

  private record ButtonGroup(
      JButton button1, JButton button2, JButton button3, JButton button4, JButton button5) {
    public void setEnabled(boolean enabled) {
      button2.setEnabled(enabled);
      button3.setEnabled(enabled);
      button4.setEnabled(enabled);
      button5.setEnabled(enabled);
    }
  }

  private record CommandActionListener(
      JFrame frame, JTable table, MyTableModel model, String command) implements ActionListener {
    @Override
    public void actionPerformed(ActionEvent e) {
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

  private record LogActionListener(JTable table, MyTableModel model) implements ActionListener {
    @Override
    public void actionPerformed(ActionEvent e) {
      int row = table.getSelectedRow();
      if (row != -1) {
        String containerId = (String) model.getValueAt(table.convertRowIndexToModel(row), 0);
        String isRunning = (String) model.getValueAt(table.convertRowIndexToModel(row), 11);

        JFrame logsFrame = new JFrame("Logs " + containerId);
        logsFrame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        logsFrame.setSize(1200, 600);
        JTextArea textArea = new JTextArea();
        textArea.setEditable(false);
        logsFrame.add(new JScrollPane(textArea));
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

    public RowSorter<MyTableModel> getSorter() {
      return new RowSorter<>() {
        final LinkedHashMap<Integer, SortKey> sortKeys = new LinkedHashMap<>();
        int[] indexesModel;
        int[] indexesView;
        Object[][] data1;
        boolean isSorted = true;

        {
          updateIndexes();
        }

        @Override
        public MyTableModel getModel() {
          return MyTableModel.this;
        }

        @Override
        public void toggleSortOrder(int column) {
          sortKeys.computeIfAbsent(column, k -> new SortKey(column, SortOrder.DESCENDING));
          sortKeys.putFirst(
              column,
              new SortKey(
                  column,
                  sortKeys.get(column).getSortOrder() == SortOrder.ASCENDING
                      ? SortOrder.DESCENDING
                      : SortOrder.ASCENDING));
          updateIndexes();
        }

        @Override
        public int convertRowIndexToModel(int index) {
          sort();
          return indexesView[index];
        }

        @Override
        public int convertRowIndexToView(int index) {
          sort();
          return indexesModel[index];
        }

        @Override
        public void setSortKeys(List<? extends SortKey> keys) {
          sortKeys.clear();
          for (SortKey key : keys) {
            sortKeys.put(key.getColumn(), key);
          }
        }

        @Override
        public List<? extends SortKey> getSortKeys() {
          return new ArrayList<>(sortKeys.values());
        }

        @Override
        public int getViewRowCount() {
          return getRowCount();
        }

        @Override
        public int getModelRowCount() {
          return getRowCount();
        }

        @Override
        public void modelStructureChanged() {
          updateIndexes();
        }

        @Override
        public void allRowsChanged() {
          updateIndexes();
        }

        @Override
        public void rowsInserted(int firstRow, int endRow) {
          updateIndexes();
        }

        @Override
        public void rowsDeleted(int firstRow, int endRow) {
          updateIndexes();
        }

        @Override
        public void rowsUpdated(int firstRow, int endRow) {
          updateIndexes();
        }

        @Override
        public void rowsUpdated(int firstRow, int endRow, int column) {
          updateIndexes();
        }

        private void updateIndexes() {
          indexesModel = new int[getModelRowCount()];
          indexesView = new int[getModelRowCount()];
          data1 = new Object[getModelRowCount()][2];
          for (int i = 0; i < getModelRowCount(); i++) {
            indexesModel[i] = i;
            indexesView[i] = i;
            data1[i][0] = i;
          }
          isSorted = false;
        }

        private void sort() {
          if (isSorted) {
            return;
          }
          isSorted = true;
          Object[] values = sortKeys.values().toArray();
          for (int j = values.length - 1; j >= 0; j--) {
            SortKey sortKey = (SortKey) values[j];
            if (sortKey.getSortOrder() == SortOrder.UNSORTED) {
              continue;
            }
            int column = sortKey.getColumn();
            int type;
            switch (column) {
              case 0, 1, 11 -> {
                for (int i = 0; i < getModelRowCount(); i++) {
                  data1[convertRowIndexToView(i)][1] = getValueAt(i, column);
                }
                type = 0;
              }
              case 2, 5 -> {
                for (int i = 0; i < getModelRowCount(); i++) {
                  String s = (String) getValueAt(i, column);
                  data1[convertRowIndexToView(i)][1] =
                      Double.parseDouble(s.substring(0, s.length() - 1));
                }
                type = 1;
              }
              case 3, 4, 6, 7, 8, 9 -> {
                Object[][] temp = {
                  {"KiB", 1024L},
                  {"MiB", 1024L * 1024L},
                  {"GiB", 1024L * 1024L * 1024L},
                  {"kB", 1000L},
                  {"MB", 1000L * 1000L},
                  {"GB", 1000L * 1000L * 1000L},
                  {"B", 1L}
                };
                for (int i = 0; i < getModelRowCount(); i++) {
                  String s = (String) getValueAt(i, column);
                  for (Object[] os : temp) {
                    if (s.endsWith((String) os[0])) {
                      data1[convertRowIndexToView(i)][1] =
                          Double.parseDouble(s.substring(0, s.length() - ((String) os[0]).length()))
                              * (Long) os[1];
                      break;
                    }
                  }
                }
                type = 1;
              }
              case 10 -> {
                for (int i = 0; i < getModelRowCount(); i++) {
                  data1[convertRowIndexToView(i)][1] =
                      Integer.parseInt((String) getValueAt(i, column));
                }
                type = 2;
              }
              default -> throw new IllegalStateException("Unexpected column value: " + column);
            }
            int finalType = 2 * type + (sortKey.getSortOrder() == SortOrder.DESCENDING ? 1 : 0);
            switch (finalType) {
              case 0 -> Arrays.sort(data1, Comparator.comparing(o -> (String) o[1]));
              case 1 ->
                  Arrays.sort(
                      data1, Comparator.comparing(o -> (String) o[1], Comparator.reverseOrder()));
              case 2 -> Arrays.sort(data1, Comparator.comparing(o -> (Double) o[1]));
              case 3 ->
                  Arrays.sort(
                      data1, Comparator.comparing(o -> (Double) o[1], Comparator.reverseOrder()));
              case 4 -> Arrays.sort(data1, Comparator.comparing(o -> (Integer) o[1]));
              case 5 ->
                  Arrays.sort(
                      data1, Comparator.comparing(o -> (Integer) o[1], Comparator.reverseOrder()));
              default -> throw new IllegalStateException();
            }
            for (int i = 0; i < getModelRowCount(); i++) {
              indexesModel[(int) data1[i][0]] = i;
              indexesView[i] = (int) data1[i][0];
            }
          }
        }
      };
    }
  }

  private static void showGUI() {
    ButtonGroup buttonGroup =
        new ButtonGroup(
            new JButton("Refresh"),
            new JButton("Stop"),
            new JButton("Start"),
            new JButton("Restart"),
            new JButton("Show Logs"));
    buttonGroup.setEnabled(false);
    JPanel panel1 = new JPanel(new GridLayout(1, 4));
    panel1.add(buttonGroup.button1());
    panel1.add(buttonGroup.button2());
    panel1.add(buttonGroup.button3());
    panel1.add(buttonGroup.button4());
    panel1.add(buttonGroup.button5());
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
              buttonGroup.setEnabled(table.getSelectedRow() != -1);
            });
    JScrollPane scrollPane = new JScrollPane(table);
    JFrame frame = new JFrame("Docker Stats");
    frame.setLayout(new BorderLayout());
    frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    frame.setSize(1600, 800);
    frame.add(panel2, BorderLayout.NORTH);
    frame.add(scrollPane, BorderLayout.CENTER);
    frame.setVisible(true);

    buttonGroup.button1().addActionListener(e -> model.update());
    buttonGroup
        .button2()
        .addActionListener(new CommandActionListener(frame, table, model, "docker stop"));
    buttonGroup
        .button3()
        .addActionListener(new CommandActionListener(frame, table, model, "docker start"));
    buttonGroup
        .button4()
        .addActionListener(new CommandActionListener(frame, table, model, "docker restart"));
    buttonGroup.button5().addActionListener(new LogActionListener(table, model));
  }
}
