package org.maggus.vpnkillswitch;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.filechooser.FileSystemView;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/**
 * Created by Mike on 2017-05-26.
 */
public class Main extends JFrame {

    static class NetIface {
        public final String name;
        public final String displayName;
        public boolean selected;

        public NetIface(String name, String displayName) {
            this.name = name;
            this.displayName = displayName;
        }

        public boolean isRunning(){
            return displayName != null;
        }

        NetIface cloneOffline(){
            return new NetIface(this.name, null);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            NetIface netIface = (NetIface) o;

            if (name != null ? !name.equals(netIface.name) : netIface.name != null) return false;
            return true;
        }

        @Override
        public int hashCode() {
            int result = name != null ? name.hashCode() : 0;
            result = 31 * result;
            return result;
        }

        @Override
        public String toString() {
            if(displayName != null)
                return name + " - " + displayName;
            else
                return name + " -offline-";
        }
    }

    static class Proc {
        public final String name;
        public final String pid;

        public Proc(String name, String pid) {
            this.name = name;
            this.pid = pid;
        }

        public boolean isRunning(){
            return pid != null;
        }

        Proc cloneOffline(){
            return new Proc(this.name, null);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Proc task = (Proc) o;

            if (name != null ? !name.equals(task.name) : task.name != null) return false;
            return true;
        }

        @Override
        public int hashCode() {
            int result = name != null ? name.hashCode() : 0;
            result = 31 * result;
            return result;
        }

        @Override
        public String toString() {
            if(pid != null)
                return name + " (PID " + pid + ")";
            else
                return name + " -offline-";
        }
    }

    public class CheckboxListCellRenderer extends JCheckBox implements ListCellRenderer {

        public Component getListCellRendererComponent(JList list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            boolean isRunning = false;
            if(value instanceof NetIface)
                isRunning = ((NetIface)value).isRunning();
            else if(value instanceof Proc)
                isRunning = ((Proc)value).isRunning();
            setComponentOrientation(list.getComponentOrientation());
            setFont(list.getFont());
            setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            if(!isRunning)
                setForeground(Color.GRAY);
            else
                setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
            setSelected(isSelected);
            setEnabled(list.isEnabled());

            setText(value == null ? "" : value.toString());

            return this;
        }
    }

    class Config {
        public static final String APP_DIR = ".vks";
        public static final String CONFIG_FILE_NAME = "vks.properties";

        public NetIface selectedNetIface;
        public List<Proc> selectedProcs = new ArrayList<Proc>();

        public File getUserDir() {
            JFileChooser fr = new JFileChooser();
            FileSystemView fw = fr.getFileSystemView();
            return fw.getDefaultDirectory();
        }

        private void loadConfig() {
            File prefDir = new File(getUserDir(), APP_DIR);
            if (!prefDir.exists() || !prefDir.isDirectory())
                prefDir.mkdirs();
            File propFile = new File(prefDir, CONFIG_FILE_NAME);
            if (!propFile.exists() || !propFile.canRead()) {
                System.err.println("! no config file " + propFile.getAbsolutePath()); // _DEBUG
                return;
            }
            try {
                Properties props = new Properties();
                props.load(new FileReader(propFile));

                String selectedNetIfaceName = props.getProperty("VPN_NET_IFACE_NAME");
                if(selectedNetIfaceName != null && !selectedNetIfaceName.isEmpty())
                    selectedNetIface = new NetIface(selectedNetIfaceName, null);

                selectedProcs.clear();
                String configItemsNum = props.getProperty("PROCS_NUM");
                int itemsNum = configItemsNum != null ? Integer.parseInt(configItemsNum) : 0;
                for (int i = 1; i <= itemsNum; i++) {
                    String nameVal = props.getProperty("PROC_NAME_" + i);
                    if (nameVal == null)
                        continue;
                    selectedProcs.add(new Proc(nameVal, null));
                }
            } catch (FileNotFoundException e) {
                System.err.println("! no config file " + propFile.getAbsolutePath() + "; " + e); // _DEBUG
            } catch (IOException e) {
                System.err.println("! can not read config file " + propFile.getAbsolutePath() + "; " + e); // _DEBUG
            }
        }

        private void saveConfig() {
            File prefDir = new File(getUserDir(), APP_DIR);
            if (!prefDir.exists() || !prefDir.isDirectory())
                prefDir.mkdirs();
            File propFile = new File(prefDir, CONFIG_FILE_NAME);
            try {
                Properties props = new Properties();

                props.setProperty("VPN_NET_IFACE_NAME", selectedNetIface != null ? selectedNetIface.name : "");

                props.setProperty("PROCS_NUM", Integer.toString(selectedProcs.size()));
                int i = 1;
                for (Proc entry : selectedProcs) {
                    props.setProperty("PROC_NAME_" + i, entry.name);
                    i++;
                }
                props.store(new FileWriter(propFile), "This is a VPN Kill Switch config file");
            } catch (IOException e) {
                System.err.println("! can not save config file " + propFile.getAbsolutePath() + "; " + e); // _DEBUG
            }
        }
    }

    static PrintStream out = System.out;
    static String OS = System.getProperty("os.name").toLowerCase();
    static SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MM.dd HH.mm.ss");

    Config config = new Config();
    volatile boolean dataDone;
    List<NetIface> lastNetIfaces;
    List<Proc> lastProcs;

    JList netIfacesList;
    JList procsList;
    JLabel selectedLbl;
    JTextArea logTa;

    public Main() throws HeadlessException {
        super("VPN Kill Switch");

        netIfacesList = new JList();
        netIfacesList.setModel(new DefaultListModel<NetIface>());
        netIfacesList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        netIfacesList.setSelectionModel(new DefaultListSelectionModel() {
            private static final long serialVersionUID = 1L;
            boolean gestureStarted = false;

            @Override
            public void setSelectionInterval(int index0, int index1) {
                if (!gestureStarted) {
                    if (isSelectedIndex(index0)) {
                        super.removeSelectionInterval(index0, index1);
                    } else {
                        clearSelection();
                        super.addSelectionInterval(index0, index1);
                    }
                }
                gestureStarted = true;
            }

            @Override
            public void setValueIsAdjusting(boolean isAdjusting) {
                if (isAdjusting == false) {
                    gestureStarted = false;
                }
            }

        });
        netIfacesList.setCellRenderer(new CheckboxListCellRenderer());
        netIfacesList.addListSelectionListener(new ListSelectionListener() {
            @Override
            public void valueChanged(ListSelectionEvent e) {
                persist();
                updateGUI();
            }
        });

        procsList = new JList();
        procsList.setModel(new DefaultListModel<Proc>());
        procsList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        procsList.setSelectionModel(new DefaultListSelectionModel() {
            private static final long serialVersionUID = 1L;
            boolean gestureStarted = false;

            @Override
            public void setSelectionInterval(int index0, int index1) {
                if (!gestureStarted) {
                    if (isSelectedIndex(index0)) {
                        super.removeSelectionInterval(index0, index1);
                        // de-select all Processes with this name
                        DefaultListModel model = (DefaultListModel) procsList.getModel();
                        if (index0 >= 0 && index0 < model.getSize()) {
                            Proc o = (Proc) model.get(index0);
                            for (int i = 0; i < model.getSize(); i++) {
                                if (o.name.equals(((Proc) model.get(i)).name)) {
                                    super.removeSelectionInterval(i, i);
                                }
                            }
                        }
                    } else {
                        //clearSelection();
                        super.addSelectionInterval(index0, index1);
                        // select all Processes with this name
                        DefaultListModel model = (DefaultListModel) procsList.getModel();
                        if (index0 >= 0 && index0 < model.getSize()) {
                            Proc o = (Proc) model.get(index0);
                            for (int i = 0; i < model.getSize(); i++) {
                                if (o.name.equals(((Proc) model.get(i)).name)) {
                                    super.addSelectionInterval(i, i);
                                }
                            }
                        }
                    }
                }
                gestureStarted = true;
            }

            @Override
            public void setValueIsAdjusting(boolean isAdjusting) {
                if (isAdjusting == false) {
                    gestureStarted = false;
                }
            }

        });
        procsList.setCellRenderer(new CheckboxListCellRenderer());
        procsList.addListSelectionListener(new ListSelectionListener() {
            @Override
            public void valueChanged(ListSelectionEvent e) {
                persist();
                updateGUI();
            }
        });

        Container contentPane = getContentPane();
        contentPane.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 2, 2, 2);
        gbc.weightx = 0;
        gbc.weighty = 0;
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.fill = GridBagConstraints.NONE;
        contentPane.add(new JLabel("Select VPN Network Interface"), gbc);
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.weightx = 1.0;
        gbc.weighty = 0.2;
        gbc.fill = GridBagConstraints.BOTH;
        contentPane.add(new JScrollPane(netIfacesList), gbc);
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.weightx = 0;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.NONE;
        contentPane.add(new JLabel("Select Processes to kill upon VPN disconnect"), gbc);
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.weightx = 1.0;
        gbc.weighty = 0.6;
        gbc.fill = GridBagConstraints.BOTH;
        contentPane.add(new JScrollPane(procsList), gbc);
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.weightx = 0;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.NONE;
        selectedLbl = new JLabel("Processes selected: <none>");
        contentPane.add(selectedLbl, gbc);
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.weightx = 1.0;
        gbc.weighty = 0.2;
        gbc.fill = GridBagConstraints.BOTH;
        logTa = new JTextArea(5, 30);
        logTa.setEditable(false);
        DefaultCaret caret = (DefaultCaret)logTa.getCaret();
        caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
        JScrollPane scrollPane = new JScrollPane(logTa);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        contentPane.add(scrollPane, gbc);

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
//        pack();
//        setLocationRelativeTo(null);
//        setVisible(true);
    }

    public void persist() {
        if(!dataDone)
            return;
        config.selectedNetIface = (NetIface) netIfacesList.getSelectedValue();

        List<Proc> selectedProcs = (List<Proc>) procsList.getSelectedValuesList();
        if (selectedProcs != null) {
            config.selectedProcs.clear();
            for (Proc proc : selectedProcs) {
                if (!config.selectedProcs.contains(proc))   // only add unique names
                    config.selectedProcs.add(proc);
            }
        }

        config.saveConfig();
    }

    public void updateGUI() {
        if(!dataDone)
            return;
        int[] selectedIndices = procsList.getSelectedIndices();
        if (selectedIndices.length > 0)
            selectedLbl.setText("Processes selected: " + selectedIndices.length);
        else
            selectedLbl.setText("Processes selected: <none>");
    }

    public void updateSystemData() {
        boolean updated = false;
        try {
            dataDone = false;
            List<NetIface> netIfaces = listNetworkInterfaces();
            if(lastNetIfaces == null || !lastNetIfaces.equals(netIfaces)){
                log("Networking Interfaces updated");
                List<NetIface>netIfaces1 = new ArrayList<NetIface>(netIfaces);
                if(config.selectedNetIface != null && !netIfaces1.contains(config.selectedNetIface))
                    netIfaces1.add(config.selectedNetIface.cloneOffline());
                //TODO: maybe sort?
                DefaultListModel netIfacesModel = new DefaultListModel<NetIface>();
                for (NetIface iface : netIfaces1) {
                    netIfacesModel.addElement(iface);
                }
                netIfacesList.setModel(netIfacesModel);
                // restore marked items
                for (int i = 0; i < netIfacesModel.getSize(); i++) {
                    NetIface o = (NetIface) netIfacesModel.get(i);
                    if (o.equals(config.selectedNetIface)) {
                        netIfacesList.addSelectionInterval(i, i);
                    }
                }
                NetIface vpnNetIface = (NetIface)netIfacesList.getSelectedValue();
                if(vpnNetIface != null && !vpnNetIface.isRunning()){
                    log("* Selected Networking Interfaces is down");
                }
                lastNetIfaces = netIfaces;
                updated = true;
            }

            List<Proc> procs = listRunningProcesses();
            if(lastProcs == null || !lastProcs.equals(procs)) {
                //out.println("Running Processes updated");
                List<Proc>procs1 = new ArrayList<Proc>(procs);
                for (Proc proc : config.selectedProcs) {
                    if (!procs1.contains(proc))
                        procs1.add(proc.cloneOffline());
                }
                Collections.sort(procs1, new Comparator<Proc>() {        // sort processes alphabetically
                    @Override
                    public int compare(Proc o1, Proc o2) {
                        return o1.name.compareTo(o2.name);
                    }
                });
                DefaultListModel procsModel = new DefaultListModel<Proc>();
                for (Proc proc : procs1) {
                    procsModel.addElement(proc);
                }
                procsList.setModel(procsModel);
                // restore marked items
                for (int i = 0; i < procsModel.getSize(); i++) {
                    Proc o = (Proc) procsModel.get(i);
                    if (config.selectedProcs.contains(o)) {
                        procsList.addSelectionInterval(i, i);
                    }
                }
                lastProcs = procs;
                updated = true;
            }
        } finally {
            dataDone = true;
            if(updated){
                persist();
                updateGUI();
                checkForVPNKills();
            }
        }
    }

    void checkForVPNKills(){
        NetIface vpnNetIface = (NetIface)netIfacesList.getSelectedValue();
        if(vpnNetIface != null && !vpnNetIface.isRunning()){
            // VPN is down, check all selected processes to see if any needs killing
            List<Proc> selectedProcs = procsList.getSelectedValuesList();
            for(Proc proc : selectedProcs){
                if(!proc.isRunning())
                    continue;
                log("* Process " + proc + " needs killing");
                killProcess(proc);
            }
        }
    }

    void log(String text){
        String line = sdf.format(Calendar.getInstance().getTime()) + ": " + text;
        out.println(line);
        if(logTa.getDocument().getLength() != 0)
            logTa.append("\n");
        logTa.append(line);
    }

    static List<NetIface> listNetworkInterfaces() {
        try {
            List<NetIface> ifaces = new ArrayList<NetIface>();
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface netIf : Collections.list(nets)) {
                if (!netIf.isUp())
                    continue;
                //out.println("Interface: " + netIf.getName() + " - \"" + netIf.getDisplayName() + "\"");
                //displaySubInterfaces(netIf);
                NetIface iface = new NetIface(netIf.getName(), netIf.getDisplayName());
                //out.println(iface);
                ifaces.add(iface);
            }
            return ifaces;
        } catch (SocketException err) {
            System.err.println("! Failed to list open network interfaces");
            err.printStackTrace();
            return null;
        }
    }

    static void displaySubInterfaces(NetworkInterface netIf) throws SocketException {
        Enumeration<NetworkInterface> subIfs = netIf.getSubInterfaces();
        for (NetworkInterface subIf : Collections.list(subIfs)) {
            out.println("\tSub Interface: " + subIf.getName() + " - \"" + subIf.getDisplayName() + "\"");
        }
    }

	
	static String[] trimQuotes(String[] s) {
		for(int i = 0; i < s.length; i++) {
			if (s[i].length() > 1) {
				s[i] = s[i].substring(1, s[i].length()-1).trim();
			}
		}		
		return s;
	}
	
    static List<Proc> listRunningProcesses() {
        try {
            List<Proc> procs = new ArrayList<Proc>();
            if (isWindows()) {
                String line;
                Process p = Runtime.getRuntime().exec("tasklist.exe /FO CSV");
                BufferedReader input = new BufferedReader(new InputStreamReader(p.getInputStream()));
                while ((line = input.readLine()) != null) {
                    //out.println(line);         // _DEBUG
                    String[] tokens = trimQuotes(line.trim().split(","));
                    if (tokens.length < 5) {						
						System.out.println("Skipping line:" + line);
                        continue;
					}
                    Double size = safeParseDouble(tokens[4]);

                    Integer pid = safeParseInteger(tokens[1]);
                    if (pid == null)
                        continue;
                    String task = tokens[0];
                    
                    Proc t = new Proc(task, pid.toString());
                    //out.println(t);           // _DEBUG
                    procs.add(t);
                }
                input.close();
            } else {
                String line;
                Process p = Runtime.getRuntime().exec("ps -e");
                BufferedReader input = new BufferedReader(new InputStreamReader(p.getInputStream()));
                while ((line = input.readLine()) != null) {
                    //out.println(line);                      // _DEBUG
                    // TODO: Parse linux processes lines here.
                    String[] tokens = line.trim().split("\\s+");
                    //out.println(Arrays.toString(tokens) + "; size=" + tokens.length);   // _DEBUG
                    if (tokens.length < 4)
                        continue;
                    Integer pid = safeParseInteger(tokens[0]);
                    if (pid == null)
                        continue;
                    String task = "";
                    for (int i = 3; i < tokens.length; i++) {
                        if (!task.isEmpty())
                            task += " ";
                        task += tokens[i];
                    }
                    Proc t = new Proc(task, pid.toString());
                    //out.println(t);                  // _DEBUG
                    procs.add(t);
                }
                input.close();
            }
            return procs;
        } catch (Exception err) {
            System.err.println("! Failed to list running process");
            err.printStackTrace();
            return null;
        }
    }

    static void killProcess(Proc proc){
        try{
            if(!proc.isRunning())
                throw new IOException("What is dead can never die");
            Runtime rt = Runtime.getRuntime();
            if (isWindows()) {
                rt.exec("taskkill /F /IM " + proc.name);     // windows only
            } else {
                rt.exec("kill -15 " + proc.pid);     // linux
		Thread.sleep(2000);
                rt.exec("kill -9 " + proc.pid);     // linux
            }
        }
        catch(IOException | InterruptedException ex){
            System.err.println("! Failed to kill process: " + proc);
            ex.printStackTrace();
        }
    }

    static Double safeParseDouble(String str) {
        try {
            str = str.trim().replaceFirst(",", ".");
            str = str.trim().replaceAll(",", "").replaceAll("K", "");
            return Double.parseDouble(str);
        } catch (Exception ex) {
            return null;
        }
    }

    static Integer safeParseInteger(String str) {
        try {
            str = str.trim();
            return Integer.parseInt(str);
        } catch (Exception ex) {
            return null;
        }
    }

    public static boolean isWindows() {
        return (OS.indexOf("windows") >= 0);
    }

    public static boolean isUnix() {
        return (OS.indexOf("nix") >= 0 || OS.indexOf("nux") >= 0 || OS.indexOf("aix") > 0);
    }

    public static void main(String args[]) {
        try {
            String os;
            if (isWindows()) {
                os = "***** Running on Windows";
            } else if (isUnix()) {
                os = "***** Running on *nix";
            } else {
                throw new IllegalArgumentException("Running on unsupported OS");
            }

            final Main gui = new Main();      // create GUI
            gui.log(os);
            gui.config.loadConfig();    // load stored preferences
            gui.updateSystemData();     // load inital network interfaces and running processes

            gui.setPreferredSize(new Dimension(400, 500));
            gui.pack();
            gui.setLocationRelativeTo(null);
            gui.setVisible(true);

            // start refresh timer
            Timer timer = new Timer(2000, new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    gui.updateSystemData();
                }
            });
            timer.start();

        } catch (Exception e) {
            System.err.println("! Critical error");
            e.printStackTrace();
        }
    }
}
