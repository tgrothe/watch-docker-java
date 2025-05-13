# watch-docker-java

## Description

This application allows you to monitor Docker containers on a remote host using SSH key-based authentication. It provides a graphical user interface (GUI) to view real-time statistics of Docker containers, such as CPU usage, memory usage, network I/O, and more. You can also start, stop, restart containers, and view their logs directly from the application.

## Requirements

1. Java 17 or higher installed on your machine.
2. Docker installed on the remote host.
3. SSH key-based authentication set up between your machine and the remote host.
4. A file named `my_host_data.txt` containing the hostname, port, and username (one per line) of the remote host.

## Usage

1. Download the latest jar file from the releases page.
2. Create a file named `my_host_data.txt` next to the jar file and add the following information:
   ```
   hostname
   port
   username
   ```
3. Open a terminal or command prompt and navigate to the directory containing the jar file.
4. Run the application using the following command:
   ```
   java -jar watch-docker-java.jar
   ```
5. In the application window, click on the "Refresh" button to load the Docker container statistics.
6. Use the buttons to start, stop, restart containers, or view their logs.
