# Create VPC
resource "aws_vpc" "vpc" {
  cidr_block = "${var.cidr_prefix}.0.0/16"

  enable_dns_hostnames = var.enable_dns_hostnames
}

moved {
  from = aws_vpc.my_vpc
  to   = aws_vpc.vpc
}

# Create public subnet
resource "aws_subnet" "public_subnet" {
  count                   = var.create_public ? 1 : 0
  vpc_id                  = aws_vpc.vpc.id
  cidr_block              = "${var.cidr_prefix}.1.0/24"
  availability_zone       = "${var.aws_region}a"
  map_public_ip_on_launch = true
}

resource "aws_subnet" "public_subnet_2" {
  count                   = var.create_public ? 1 : 0
  vpc_id                  = aws_vpc.vpc.id
  cidr_block              = "${var.cidr_prefix}.4.0/24"
  availability_zone       = "${var.aws_region}b"
  map_public_ip_on_launch = true
}

# Create private subnet
resource "aws_subnet" "private_subnet" {
  vpc_id            = aws_vpc.vpc.id
  cidr_block        = "${var.cidr_prefix}.2.0/24"
  availability_zone = "${var.aws_region}a"
}

# Create private subnet
resource "aws_subnet" "private_subnet_2" {
  vpc_id            = aws_vpc.vpc.id
  cidr_block        = "${var.cidr_prefix}.3.0/24"
  availability_zone = "${var.aws_region}b"
}

resource "aws_route_table" "private" {
  vpc_id = aws_vpc.vpc.id
}

# Internet Gateway for public subnet
resource "aws_internet_gateway" "igw" {
  vpc_id = aws_vpc.vpc.id
}

resource "aws_route" "private_egress" {
  route_table_id         = aws_route_table.private.id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.vpc_nat[0].id
}

resource "aws_route_table_association" "private-1" {
  subnet_id      = aws_subnet.private_subnet.id
  route_table_id = aws_route_table.private.id
}

resource "aws_route_table_association" "private-2" {
  subnet_id      = aws_subnet.private_subnet_2.id
  route_table_id = aws_route_table.private.id
}
resource "aws_eip" "vpc-nat-1a" {
  depends_on = [aws_internet_gateway.igw]
  vpc        = true
}

resource "aws_nat_gateway" "vpc_nat" {
  count         = var.create_public ? 1 : 0
  allocation_id = aws_eip.vpc-nat-1a.id
  subnet_id     = aws_subnet.public_subnet[0].id
  depends_on    = [aws_internet_gateway.igw]
}

# Create route table for public subnet
resource "aws_route_table" "public_route_table" {
  count  = var.create_public ? 1 : 0
  vpc_id = aws_vpc.vpc.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.igw.id
  }
}

# Associate public route table with public subnet
resource "aws_route_table_association" "public_subnet_association" {
  count          = var.create_public ? 1 : 0
  subnet_id      = aws_subnet.public_subnet[0].id
  route_table_id = aws_route_table.public_route_table[0].id
}
