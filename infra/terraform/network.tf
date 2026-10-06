#---------------------------get 2 AZ here---------------------------
data "aws_availability_zones" "available" {
  state = "available"
}

#---------------------------Then configure 2 VPC---------------------------
resource "aws_vpc" "main" {
  cidr_block = var.vpc_cidr #assign vpc_cidr

  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = {
    Name = "finguard-vpc"
  }
}

#---------------------------IGW config here------------------------------
resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id #IGW need to attach to VPC (Dependency)

  tags = {
    Name = "finguard-igw"
  }
}

#---------------------------Public subnets config here---------------------------
resource "aws_subnet" "public" {
  count = 2

  vpc_id            = aws_vpc.main.id
  cidr_block        = var.public_subnet_cidrs[count.index]
  availability_zone = data.aws_availability_zones.available.names[count.index]

  #will create aws_subnet.public[0] & aws_subnet.public[1]
  #CIDR[0] + AZ[0], CIDR[1] + AZ[1]

  tags = {
    Name = "finguard-public-${count.index + 1}"
  }
}

#---------------------------Private subnets config here---------------------------
resource "aws_subnet" "app_private" {
  count = 2

  vpc_id            = aws_vpc.main.id
  cidr_block        = var.app_subnet_cidrs[count.index]
  availability_zone = data.aws_availability_zones.available.names[count.index]

  tags = {
    Name = "finguard-app-private-${count.index + 1}"
  }
}

#---------------------------Public Route Table config here---------------------------
#make public subnet become "really" public
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = {
    Name = "finguard-public-rt"
  }
}

#---------------------------Associate Route Table to public subnet--------------------
resource "aws_route_table_association" "public" {
  count = 2

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

#--------------------------allocate Two Elastic IP for NAT Gateway--------------------
resource "aws_eip" "nat" {
  count = 2

  domain = "vpc"

  tags = {
    Name = "finguard-nat-eip-${count.index + 1}"
  }
}

#--------------------------config two NAT Gateway------------------------------------
resource "aws_nat_gateway" "main" {
  count = 2

  allocation_id = aws_eip.nat[count.index].id
  subnet_id     = aws_subnet.public[count.index].id

  #Here has implicities dependencies: NAT-> EIP, NAT->Public subnet


  depends_on = [
    aws_internet_gateway.main
  ]

  #Here has explicities dependency: NAT -> IGW

  tags = {
    Name = "finguard-nat-${count.index + 1}"
  }
}

#-------------------------Configure Privacy routing tables-----------------------------------
resource "aws_route_table" "app_private" {
  count = 2

  vpc_id = aws_vpc.main.id

  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.main[count.index].id
  }

  tags = {
    Name = "finguard-app-private-rt-${count.index + 1}"
  }
}

#------------------------associate Privacy subnets to privacy tables-----------------------
resource "aws_route_table_association" "app_private" {
  count = 2

  subnet_id      = aws_subnet.app_private[count.index].id
  route_table_id = aws_route_table.app_private[count.index].id
}

#-------------------------config DB privacy subnet -----------------------------------
resource "aws_subnet" "db_private" {
  count = 2

  vpc_id            = aws_vpc.main.id
  cidr_block        = var.db_subnet_cidrs[count.index]
  availability_zone = data.aws_availability_zones.available.names[count.index]

  tags = {
    Name = "finguard-db-private-${count.index + 1}"
  }
}

#-------------------------DB route table ------------------------------------------------
resource "aws_route_table" "db_private" {
  vpc_id = aws_vpc.main.id
  #这里暂时没给DB route

  tags = {
    Name = "finguard-db-private-rt"
  }
}

#----------------------associate DB and route table------------------------------------
resource "aws_route_table_association" "db_private" {
  count = 2

  subnet_id      = aws_subnet.db_private[count.index].id
  route_table_id = aws_route_table.db_private.id
}
