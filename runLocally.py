import subprocess

input_matrix = []

with open('matrix_input.txt', 'r') as file:
    for line in file:
        matrix_row = []
        matrix_row = [int(val) for val in line.strip().split(" ")]
        input_matrix.append(matrix_row)
        
        
for line in input_matrix:
    print(line)

list_of_outgoing = []
list_of_incoming = []

# I need to implement how we handle how we start all the nodes.
# Here its just hard coded for one.
node = 1

for row in range(len(input_matrix)):
    for val in range(len(input_matrix[row])):
        if val > row and input_matrix[row][val] == 1 and (row == node or val == node):
            outgoing = str(val) + "to" + str(row)
            list_of_outgoing.append(outgoing)
        elif val < row and input_matrix[row][val] == 1 and (row == node or val == node):
            incoming = str(val) + "to" + str(row)
            list_of_incoming.append(incoming)
            
            
command = ["java", "HelloWorld"]

# This is only for 1 node for now
command.append(str(node))
for n in range(len(list_of_incoming)):
    command.append("localhost")
    command.append(list_of_incoming[n])
    command.append(list_of_outgoing[n])
    
print(command)

        
# result = subprocess.run(command, capture_output=True, text=True)