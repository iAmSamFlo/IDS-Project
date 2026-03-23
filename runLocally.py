import subprocess
import threading
import time

class Node:
    def __init__(self, input_matrix, virtual_ring, node):
        self.node = node
        self.list_of_outgoing = []
        self.list_of_incoming = []
        
        if node-1 == 0:
            self.left = virtual_ring[node - 3]
        else:
            self.left = virtual_ring[node -2]
        self.right = virtual_ring[node]
        
        for row in range(len(input_matrix)):
            for val in range(len(input_matrix[row])):
                if val > row and input_matrix[row][val] == 1 and (row == node-1 or val == node-1):
                    outgoing = str(val+1) + "to" + str(row+1)
                    self.list_of_outgoing.append(outgoing)
                elif val < row and input_matrix[row][val] == 1 and (row == node-1 or val == node-1):
                    incoming = str(val+1) + "to" + str(row+1)
                    self.list_of_incoming.append(incoming)
                    
            
        command = ["java", "test/HelloWorld"]
        command.append(str(node))                   # ID of Node
        command.append(str(self.left))              # When sending to the left which node is allowed to be destination of message
        command.append(str(self.right))             # When sending to the right which node is all to be dest of message
        for n in range(len(self.list_of_incoming)):
            command.append("localhost")
            command.append(self.list_of_incoming[n])
            command.append(self.list_of_outgoing[n])
            
            # need to append left and right nodes here which the node is allowed to send message to.
            
        print(command)

        self.process = subprocess.Popen(
                command, 
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                bufsize=1
        )
        
        self.monitor_thread = threading.Thread(target=self.read_output, daemon=True)
        self.monitor_thread.start()
            
            
    def read_output(self):
        for line in self.process.stdout:
            if line:
                print(f"[Node {self.node} says]: {line.strip()}")
            
    def sender(self, command):
        self.process.stdin.write(command + "\n")
        self.process.stdin.flush()
            
            

input_matrix = []
with open('matrix_input.txt', 'r') as file:
    for line in file:
        matrix_row = []
        matrix_row = [int(val) for val in line.strip().split(" ")]
        input_matrix.append(matrix_row)
        
        
for line in input_matrix:
    print(line)
    
print("\n Please input the neighbor list of the virtual ring like the following below (first and last needs be the same): \n")
print("1-2-3-4-5-1 \n")
    
virtual_ring = [int(val) for val in input("Enter here: ").strip().split("-")]

nodes = [Node(input_matrix, virtual_ring, i) for i in range(1,len(input_matrix)+1)]

# remove later maybe, issue with the parallel stuff
time.sleep(1)

print("\nYou can now start sending messages left or right")
print("Plese specify which direction, starting-node, and message to send")
print("Example: left 2 Hello World!")
print("Example: right 4 Hello World!")

while True:
    input_params = input().strip().split(" ", 2)
    input_params[0] = input_params[0].lower()
    input_params[1] = int(input_params[1])
    if (input_params[0] == "left" or input_params[0] == "right") and 1 <= input_params[1] <= len(nodes):
        data_sent = input_params[0] + " " + input_params[2]
        nodes[input_params[1]].sender(data_sent)
    else:
        print(input_params)
        print("Issue with input line, try again:")
        
    
    