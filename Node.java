import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.DeliverCallback;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Random;
import java.util.Scanner;
import java.util.concurrent.TimeoutException;
import java.lang.Thread;
import com.rabbitmq.client.Channel;


// RULES and explainations are below
// This version has many edgeguards against bad input. It can be shortend for better readability at the cost of throwing exceptions when receiving bad channel input or similar breaches of contract.

enum Event_type{
  PING, PONG, GO, START, INVALID
}

enum State{
  NOT_INITIALIZED, INITIALIZED
}

class Event {
  final Event_type type;
  final int parameter;
  

  public Event(Event_type type){
    this.type = type;
    this.parameter = -1;
  }

  public Event(Event_type type,int parameter){
    this.type = type;
    this.parameter = parameter;
  }
}

public class Node {
  private static boolean DEBUG = false;
  private static State state = State.NOT_INITIALIZED;
  private static int ID;
  private static Channel in, out;
  private static String in_queue_name, out_queue_name;

  private static Channel buildChannel(String channelname) throws IOException, TimeoutException{
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost("localhost");
    Connection connection = factory.newConnection();
    Channel channel = connection.createChannel();
    boolean durable = false;
    channel.queueDeclare(channelname, durable, false, false, null);
    return channel;
  }
  
  private static byte[] pack(Event event){
    return (event.type + " " + event.parameter).getBytes(); 
  }
  private static Event unpack(byte[] bytes){
    try {
    String s = new String(bytes, "UTF-8");
    return new Event(
      Event_type.valueOf(s.split(" ")[0]), 
      Integer.valueOf(s.split(" ")[1]));
    } catch (UnsupportedEncodingException e) {
      System.err.println("[ERROR]: Received message has wrong encoding");
      System.exit(-1);
      return new Event(Event_type.INVALID);
    } catch (IllegalArgumentException e) {
      System.err.println("[ERROR]: Unable to interpret message as event");
      return new Event(Event_type.INVALID);
    }
    }


  public static void main(String[] argv) throws Exception {
    if (argv.length < 3) {
      System.out.println("usage: pingpong <id> <in_queue_name> <out_queue_name>");
      return;
    }
    ID = Integer.valueOf(argv[0]);
    in_queue_name = argv[1];
    out_queue_name = argv[2];

    try {  
      in = buildChannel(in_queue_name);
      out = buildChannel(out_queue_name);
    } catch (IOException | TimeoutException e) {
      e.printStackTrace();
      return;
    }

    Random r = new Random();

    DeliverCallback deliverCallback = (consumerTag, delivery) -> {
      Event event = unpack(delivery.getBody());

      try{
        //simulate unpredictable transmission behaviour
        Thread.sleep(r.nextInt(3000));
        if (DEBUG) System.out.println("> Received '" + new String(delivery.getBody(),"UTF-8") + "'");
        handleEvent(event);
      } catch (InterruptedException | IOException e){
        e.printStackTrace();
        System.exit(-1);
      }
      in.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
    };
    boolean autoAck = false; // acknowledgment is covered below
    Thread t = new Thread(
      () -> {
        do {
        try{
          in.basicConsume(in_queue_name, autoAck, deliverCallback, consumerTag -> { });
        } catch (IOException e) {
          System.err.println("[IO-Exception] while receiving: " + (e.getMessage()));
        }
        } while(true);
      } 
    );
    t.start();
    receive_commands();
  }

  private static void receive_commands(){
        Scanner sc = new Scanner(System.in);
        while(true) {
          String _trash = sc.nextLine();
          try{
            System.out.println("Delivering Start Command");
            handleEvent(new Event(Event_type.START));
          } catch (IOException e) {}
        }
  }

  private static synchronized void handleEvent(Event msg) throws IOException{
    switch (msg.type) {
      //RULE 1: If I receive a Start command and am not initalized, send a GO(ID) to the other node
      //RULE 2: Ignore Start if already initialized
      case Event_type.START:
        if (DEBUG) System.out.println("Handling START Event");
        if (state == State.NOT_INITIALIZED){
          out.basicPublish("",out_queue_name,null,pack(new Event(Event_type.GO,ID)));
        }
        break;
      //RULE 3: If I receive a Go(id) and I am not Initialized 
      //  If I win (I have smaller ID): send a Ping and set own state to Initalized
      //  If I lose (I have higher ID): send the GO(ID) back
      //  [Explaination]: If I lose the other process must win in their RULE 3, so they will start the pingpong
      //RULE 4: Ignore GO when initialized
      case Event_type.GO:
        if (DEBUG) System.out.println("Handling GO Event");
          if (state == State.NOT_INITIALIZED){
            if (msg.parameter > ID) {
              state = State.INITIALIZED;
              out.basicPublish("",out_queue_name,null,pack(new Event(Event_type.PING)));
              if (DEBUG) System.out.println(String.format("Received Go and won with %d < %d, sending ping", ID , msg.parameter));
              System.out.println("ping");
            } else {
              if (DEBUG) System.out.println(String.format("Received Go and lost with %d > %d sending GO(%d) back", ID , msg.parameter, ID));
              out.basicPublish("",out_queue_name,null,pack(new Event(Event_type.GO,ID)));
              if ((msg.parameter == ID) ){
                System.err.println(String.format("[ERROR]: BOTH PROCESSES HAVE SAME ID %d %d, BREACH OF CONTRACT => TERMINATING",ID , msg.parameter));
                System.exit(-1);
              }
            }
          }
        break;
      //RULE 5: If I receive a Ping I answer with Pong and set my state to initalized 
      //[Explanation]: When receiving a Ping a the other process has already determined they have priority 
      //[Explanation]: No differentiation between state.INITALIZED and state.NOT_INITALIZED needed as both cases respond with pong and want to have the initalized state afterwards. The assignment can be therefore be redundant but that does no harm.
      case Event_type.PING:
        if (DEBUG) System.out.println("Handling PING Event");
        state = State.INITIALIZED;
        System.out.println("pong");
        out.basicPublish("",out_queue_name,null,pack(new Event(Event_type.PONG)));
        break;
      //RULE 6: If I receive a PONG respond with a PING
      //[Explanation]: To receive a PONG I must have send a PING beforehand, therefore I am necessarly initalized. No differentiation needed.
      case Event_type.PONG:
        if (DEBUG) System.out.println("Handling PONG Event");
        out.basicPublish("",out_queue_name,null,pack(new Event(Event_type.PING)));
        System.out.println("ping");
        break;

      case Event_type.INVALID:
        System.err.println("[WARNING]: Invalid message will be ignored");
        
        break;

      default:
        System.err.println("[ERROR] Illegal message type");
        break;
    }
    
  }
}

