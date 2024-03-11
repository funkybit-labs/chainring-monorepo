# Create VPC
resource "aws_vpc" "my_vpc" {
  cidr_block = "${var.cidr_prefix}.0.0/16"
}

# Create public subnet
resource "aws_subnet" "public_subnet" {
  count                   = var.create_public ? 1 : 0
  vpc_id                  = aws_vpc.my_vpc.id
  cidr_block              = "${var.cidr_prefix}.1.0/24"
  availability_zone       = "${var.aws_region}a"
  map_public_ip_on_launch = true
}

# Create private subnet
resource "aws_subnet" "private_subnet" {
  vpc_id            = aws_vpc.my_vpc.id
  cidr_block        = "${var.cidr_prefix}.2.0/24"
  availability_zone = "${var.aws_region}a"
}

# Internet Gateway for public subnet
resource "aws_internet_gateway" "igw" {
  count  = var.create_public ? 1 : 0
  vpc_id = aws_vpc.my_vpc.id
}

# Create route table for public subnet
resource "aws_route_table" "public_route_table" {
  count  = var.create_public ? 1 : 0
  vpc_id = aws_vpc.my_vpc.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.igw[0].id
  }
}

# Associate public route table with public subnet
resource "aws_route_table_association" "public_subnet_association" {
  count  = var.create_public ? 1 : 0
  subnet_id      = aws_subnet.public_subnet[0].id
  route_table_id = aws_route_table.public_route_table[0].id
}
