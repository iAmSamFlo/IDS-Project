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


// This class represents a singe entry in a routing table
class RoutingEntry implements Serializable{
  int distance;
  int nextHop;

  RoutingEntry(int distance, int nextHop){
    this.distance = distance;
    this.nextHop = nextHop;
  }

}

//This class represents an entire routing table.
//It groups the entries in a ConcurrentHashMap to prevent issues from Multithreading
//The ConcurrentHashMap also allows to access the data fast when the target id is known
class RoutingTable{
  private ConcurrentHashMap<Integer,RoutingEntry> entries = new ConcurrentHashMap<Integer,RoutingEntry>();
  
  //This class is initialized with a singe routing entry to itself with distance 0
  RoutingTable(int own_id){
    entries.put(own_id, new RoutingEntry(0,own_id));
  }

  //This getter function returns the entries. 
  // TODO: It could be improved by making the returned version read only, but I currently don't know how 
  ConcurrentHashMap<Integer,RoutingEntry> get_entries(){
    return entries;
  }


  //This function updated the nodes own routing table based on the recieved routing table from its neighbor
  synchronized Boolean update_table(ConcurrentHashMap<Integer,RoutingEntry> other, int other_table_owner) {
    Boolean changes_were_made = false;

    Iterator<ConcurrentHashMap.Entry<Integer, RoutingEntry> > 
      i = other.entrySet().iterator();
    while (i.hasNext()) {
       ConcurrentHashMap.Entry<Integer, RoutingEntry> other_entry = i.next();

       //add all previously unknown nodes
       if(entries.get(other_entry.getKey()) == null) {
        changes_were_made = true;

        RoutingEntry new_entry = new RoutingEntry(other_entry.getValue().distance + 1, other_table_owner);
        entries.put(other_entry.getKey(), new_entry);
      }
      //add all new faster connections
      //TODO: Here we need to update the Enries if the new distance is shorter than the old known one
    }
    return changes_were_made;
  }
}

// The types of possible Events
enum Event_type{
  TABLE_UPDATE, 
}

// Different Debug Levels for testing and demos
enum DEBUG_LEVEL {
  TRACE, DEBUG, INFO, WARN, ERROR
}

// The actual Events/Messages that are serialized to be transmitted via rabbitMQ
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

// A custom error Class to seperate errors during network setup from errors during Runtime
class SetupError extends Exception{
}

// This Class represents the Edge from the current node to another node.
// As all connections a bidirectional it has an in and an out channel
// The out channel 
class Edge{
  private static Boolean autoAck = true;
  final Channel in;
  final String in_channel_name;
  final Channel out;
  final String out_channel_name;

  // Constructor that sets up the channels to the target host
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

  // internal helper function to set up channels in a similar way then the PingPong
  private static Channel buildChannel(String channelname, String hostname) throws IOException, TimeoutException {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(hostname);
    Connection connection = factory.newConnection();
    Channel channel = connection.createChannel();
    channel.queueDeclare(channelname, false, false, false, null);
    return channel;
  }

  // method to send actual data over the edge
  void send(byte[] body) throws IOException{
    out.basicPublish("",out_channel_name,null,body);
  }

  // method to create threads that handle the registerd callback when a message over this edge is received
  Thread register_message_handler(DeliverCallback deliverCallback){
    return new Thread(
          () -> {
            do {
              try {
                in.basicConsume(in_channel_name, autoAck, deliverCallback, cancelCallback -> {
                });
              } catch (IOException e) {
                System.err.println("[IO-Exception] while receiving: " + (e.getMessage()));
              }
            } while (true);
          });

  }
}

//This class represents a Node in our network. It is the same Class for all Nodes and has no prior information about the rest of the network
public class Node {
  private static DEBUG_LEVEL debug_level = DEBUG_LEVEL.INFO;
  private static int ID;
  private static RoutingTable physical_routing_table;
  private static ArrayList<Edge> edges = new ArrayList<Edge>();
  private static Boolean initalized = false;

  // This method sets up the edges, registers the callbacks for incoming messages and lets the user join the network
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
      Thread t = edge.register_message_handler(default_message_handler); 
      t.start();     
    }  
  join_network();
  }

  // The default message handler that is used to handle incoming messages over all edges
  private static DeliverCallback default_message_handler = (consumerTag, delivery) -> {
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

  // This method converts an Event to bytes in order to send it to another node using rabbitMQ
  private static byte[] pack(Event event) throws IOException{
    ByteArrayOutputStream bos = new ByteArrayOutputStream(); 
    ObjectOutputStream oos = new ObjectOutputStream(bos); 
    oos.writeObject(event);
    return bos.toByteArray();
  }

  // This method deserializes the received bytes back into an object of type Message
  private static Event unpack(byte[] data) throws IOException, ClassNotFoundException{
    ByteArrayInputStream bis = new ByteArrayInputStream(data);
    ObjectInputStream ois = new ObjectInputStream(bis); 
    return (Event) ois.readObject();
  }

  // This function waits for user input to initialize the network joining procedure
  // This mainly consits of sharing ones own Routing table to adjacent nodes.
  // As the own node is part of that table adjacent nodes will adapt their tables respectivly.
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


  // This is the function that handles the different events.
  // All Rules for our (physical layer) Algorithm are Here
  // TODO: Implement those rules
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

