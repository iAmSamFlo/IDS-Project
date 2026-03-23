import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.DeliverCallback;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.lang.Thread;
import com.rabbitmq.client.Channel;

class RoutingEntry implements Serializable{
  int distance;
  int nextHop;

  RoutingEntry(int distance, int nextHop){
    this.distance = distance;
    this.nextHop = nextHop;
  }

}

class RoutingTable{
  private ConcurrentHashMap<Integer,RoutingEntry> entries = new ConcurrentHashMap<Integer,RoutingEntry>();

  RoutingTable(int own_id){
    entries.put(own_id, new RoutingEntry(0,-1));
  }


  ConcurrentHashMap<Integer,RoutingEntry> get_entries(){
    return entries;
  }


  synchronized Boolean update_table(ConcurrentHashMap<Integer,RoutingEntry> other, int other_table_owner) {
    //TODO:
    Boolean changes_were_made = false;

    Iterator<ConcurrentHashMap.Entry<Integer, RoutingEntry> > 
      i = other.entrySet().iterator();
    while (i.hasNext()) {
       ConcurrentHashMap.Entry<Integer, RoutingEntry> other_entry = i.next();
      if(entries.get(other_entry.getKey()) == null) {
        changes_were_made = true;

        RoutingEntry new_entry = new RoutingEntry(other_entry.getValue().distance + 1, other_table_owner);
        entries.put(other_entry.getKey(), new_entry);
      }
      //TODO update when shorter paths are found
    }

    return changes_were_made;
  }
}


enum Event_type{
  TABLE_UPDATE, 
}

enum DEBUG_LEVEL {
  TRACE, DEBUG, INFO, WARN, ERROR
}

class Event implements Serializable{
  final Event_type type;
  final ConcurrentHashMap<Integer,RoutingEntry> routing_table_entries;

  public Event(Event_type type) {
    this.type = type;
    this.routing_table_entries = null;
  }

  public Event(Event_type type, ConcurrentHashMap<Integer,RoutingEntry> routing_table_entries) {
    this.type = type;
    this.routing_table_entries = routing_table_entries;
  }
}

class SetupError extends Exception{
}

class Edge{
  final Channel in;
  final String in_channel_name;
  final Channel out;
  final String out_channel_name;

  Edge (String hostname, String in_channel_name, String out_channel_name) throws SetupError{
    this.in_channel_name = in_channel_name;
    this.out_channel_name = out_channel_name;
    try{
      this.in = buildChannel(in_channel_name, hostname);
      this.out = buildChannel(out_channel_name, hostname);
    } catch (IOException | TimeoutException e) {
      System.err.println("Setup Error in channel creation: " + e.getMessage());
      throw new SetupError();
    }
  
  
  }

  private static Channel buildChannel(String channelname, String hostname) throws IOException, TimeoutException {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(hostname);
    Connection connection = factory.newConnection();
    Channel channel = connection.createChannel();
    channel.queueDeclare(channelname, false, false, false, null);
    return channel;
  }

  void send(byte[] body) throws IOException{
    out.basicPublish("",out_channel_name,null,body);
  }

}

public class Node {
  private static DEBUG_LEVEL debug_level = DEBUG_LEVEL.DEBUG;
  private static int ID;
  private static RoutingTable physical_routing_table;
  private static ArrayList<Edge> edges = new ArrayList<Edge>();
  private static Boolean autoAck = true;
  private static Boolean initalized = false;

  public static void main(String[] argv) throws Exception {
    for (String s : argv) System.out.println(s);
    if (argv.length < 3 || argv.length %3 != 1) {
      System.out.println("usage: node <id> <hostname1> <in_queue_name_1> <out_queue_name_1> <hostname2> <in_queue_name_2> <out_queue_name_2> ... ");
      return;
    }
    ID = Integer.valueOf(argv[0]);
    physical_routing_table = new RoutingTable(ID);

    for (int i = 1; i + 2 < argv.length; i+=3){
      edges.add(new Edge(argv[i], argv[i+1], argv[i+2]));
    }
    
    for (Edge edge : edges) {
      DeliverCallback deliverCallback = (consumerTag, delivery) -> {
        try {
          if (debug_level == DEBUG_LEVEL.TRACE){
            System.out.println("> Received '" + new String(delivery.getBody(), "UTF-8") + "'");
          }
          handleEvent(unpack(delivery.getBody()));


        } catch (IOException|ClassNotFoundException e) {
          e.printStackTrace();
          System.exit(-1);
        }
      }; 
      Thread t = new Thread(
          () -> {
            do {
              try {
                edge.in.basicConsume(edge.in_channel_name, autoAck, deliverCallback, cancelCallback -> {
                });
              } catch (IOException e) {
                System.err.println("[IO-Exception] while receiving: " + (e.getMessage()));
              }
            } while (true);
          });
      t.start();
    
    }  
  join_network();
  }

  private static byte[] pack(Event event) throws IOException{
    ByteArrayOutputStream bos = new ByteArrayOutputStream(); 
    ObjectOutputStream oos = new ObjectOutputStream(bos); 
    oos.writeObject(event);
    return bos.toByteArray();
  }

  private static Event unpack(byte[] data) throws IOException, ClassNotFoundException{
    ByteArrayInputStream bis = new ByteArrayInputStream(data);
    ObjectInputStream ois = new ObjectInputStream(bis); 
    return (Event) ois.readObject();
  }

  private static void join_network() throws IOException {
    System.out.print("Press [Enter] to join the Network >");
    Scanner sc = new Scanner(System.in);
    sc.nextLine();
    sc.close();
    System.out.println("[Joining]");
    
    byte[] body = pack(new Event(Event_type.TABLE_UPDATE,physical_routing_table.get_entries()));

    if (!initalized) {
      for (Edge edge: edges) {
        try{
          edge.send(body);
        } catch (IOException e) {
          System.err.println(e.getMessage());
          e.printStackTrace();
        }
      }
    }

    //Go into Idle
    while (true) {
      try {
      Thread.sleep(1000);
      } catch (InterruptedException e){
        e.printStackTrace();
        return;
      }
      
    }
  }


  private static synchronized void handleEvent(Event event) throws IOException{
    switch (event.type) {
      case Event_type.TABLE_UPDATE:
        if (debug_level == DEBUG_LEVEL.TRACE || debug_level== DEBUG_LEVEL.DEBUG) System.out.println("Handling Table Update Event");
        Boolean changes_were_made = physical_routing_table.update_table(event.routing_table_entries, ID);
        if (changes_were_made) {
          System.out.print("Table Updated");
        } else {
          System.out.print("Nothing new");
        }
        break;
    }
  }

}

