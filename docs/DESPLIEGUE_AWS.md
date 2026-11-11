# Despliegue AWS desde cero — Pedidos360 / SummitLab

> Guía para el compañero encargado de la nube. Está pensada para alguien que **parte desde cero**:
> crea la cuenta, la red, los security groups, las 4 instancias EC2, instala Docker, levanta Kafka
> y los 8 microservicios, y verifica el flujo completo.
>
> **Región recomendada:** `us-east-1` (N. Virginia). Si usan otra, reemplácenla en todos los comandos.
>
> **Costo estimado con 4 × `t3.small` encendidas 24/7:** ~USD 60–75/mes. Para la demo pueden
> apagarlas (`stop-instances`) y pagar solo el almacenamiento EBS (~USD 3/mes por instancia).

---

## 0. Arquitectura objetivo

```text
                         Internet
                            │
                    ┌───────▼────────┐
                    │  EC2 frontend  │  nginx :80  (público, Elastic IP)
                    │  10.0.1.10     │  sirve React + proxy /api/*
                    └───┬───┬───┬────┘
        ┌───────────────┘   │   └────────────────┐
        │                   │                    │
┌───────▼────────┐  ┌───────▼────────┐  ┌────────▼─────────┐
│ EC2 backend-1  │  │ EC2 backend-2  │  │  EC2 backend-3   │
│ 10.0.1.11      │  │ 10.0.1.12      │  │  10.0.1.13       │
│ productos 8081 │  │ notificac.8084 │  │ pagos 8087       │
│ pedidos   8082 │  │ carrito   8085 │  │ recomend. 8088   │
│ usuarios  8083 │  │ envíos    8086 │  │ Kafka 29092      │
│                │  │                │  │ Kafka UI 8090    │
└───────┬────────┘  └───────┬────────┘  └────────┬─────────┘
        │                   │                    │
        └───────────────────┴────────────────────┘
                    Kafka (broker en backend-3)
```

### Tópicos Kafka

| Tópico | Productor | Consumidores (consumer group) |
|---|---|---|
| `pedidos.creados` | pedidos | `productos-stock`, `pagos-pedidos`, `recomendaciones-pedidos`, `notificaciones-pedidos` |
| `pagos.procesados` | pagos | `pedidos-pagos`, `envios-pagos`, `notificaciones-pagos`, `recomendaciones-pagos` |
| `pagos.fraude.detectado` | pagos | `pedidos-fraude`, `notificaciones-fraude` |
| `*.DLT` | Spring Kafka | dead-letter de cada tópico |

### Puertos

| Instancia | Servicio | Puerto host |
|---|---|---|
| frontend | nginx | 80 / 443 |
| backend-1 | servicio-productos | 8081 |
| backend-1 | servicio-pedidos | 8082 |
| backend-1 | servicio-usuarios | 8083 |
| backend-2 | servicio-notificaciones | 8084 |
| backend-2 | servicio-carrito | 8085 |
| backend-2 | servicio-envíos | 8086 |
| backend-3 | servicio-pagos | 8087 |
| backend-3 | servicio-recomendaciones | 8088 |
| backend-3 | Kafka (listener externo) | 29092 |
| backend-3 | Kafka UI | 8090 |

---

## 1. Repositorios

| Repo | Contenido |
|---|---|
| `Ev1CloudNative_Mountain` | Frontend React/Vite + nginx |
| `Ev1CloudNative_Mountain_backend` | productos, pedidos, usuarios |
| `Ev1CloudNative_Mountain_backend_parte2` | notificaciones, carrito, envíos |
| `Ev1CloudNative_Mountain_backend_parte3` | pagos, recomendaciones, Kafka (broker + UI) |

> Reemplacen `TU-USUARIO` por la cuenta de GitHub donde estén creados los repos.

---

## 2. Cuenta AWS e IAM (primera vez)

### 2.1 Crear la cuenta
1. Entrar a <https://aws.amazon.com/> → **Create an AWS Account**.
2. Usar un correo institucional/personal, verificar tarjeta (cargo de USD 1 reembolsable).
3. Elegir plan **Basic support (free)**.

### 2.2 Proteger la cuenta root
1. Activar **MFA** en el usuario root (IAM → Security credentials → Assign MFA).
2. **No usar root** para el día a día.

### 2.3 Crear usuario administrador
1. IAM → Users → **Create user** → nombre `pedidos360-admin`.
2. Marcar **Provide user access to the AWS Management Console**.
3. Attach policies directly → `AdministratorAccess`.
4. Guardar usuario y contraseña.
5. Entrar con ese usuario, activar MFA y crear **Access keys** (CLI) → guardar `Access Key ID` y `Secret Access Key`.

### 2.4 Instalar AWS CLI v2
- Windows: <https://awscli.amazonaws.com/AWSCLIV2.msi>
- Linux/macOS: instrucciones oficiales de AWS CLI v2.

```bash
aws configure
# AWS Access Key ID:     <tu access key>
# AWS Secret Access Key: <tu secret>
# Default region name:   us-east-1
# Default output format: json
aws sts get-caller-identity   # debe mostrar tu cuenta
```

---

## 3. Red: VPC, subred, Internet Gateway y rutas

Se puede hacer por consola o por CLI. Los comandos CLI dejan todo reproducible.

### 3.1 Crear VPC y subred

```bash
# VPC 10.0.0.0/16
VPC_ID=$(aws ec2 create-vpc \
  --cidr-block 10.0.0.0/16 \
  --tag-specifications 'ResourceType=vpc,Tags=[{Key=Name,Value=vpc-pedidos360}]' \
  --query 'Vpc.VpcId' --output text)
aws ec2 modify-vpc-attribute --vpc-id $VPC_ID --enable-dns-hostnames

# Subred pública 10.0.1.0/24 en us-east-1a
SUBNET_ID=$(aws ec2 create-subnet \
  --vpc-id $VPC_ID --cidr-block 10.0.1.0/24 \
  --availability-zone us-east-1a \
  --tag-specifications 'ResourceType=subnet,Tags=[{Key=Name,Value=subnet-pedidos360-public}]' \
  --query 'Subnet.SubnetId' --output text)
aws ec2 modify-subnet-attribute --subnet-id $SUBNET_ID --map-public-ip-on-launch

# Internet Gateway
IGW_ID=$(aws ec2 create-internet-gateway \
  --tag-specifications 'ResourceType=internet-gateway,Tags=[{Key=Name,Value=igw-pedidos360}]' \
  --query 'InternetGateway.InternetGatewayId' --output text)
aws ec2 attach-internet-gateway --vpc-id $VPC_ID --internet-gateway-id $IGW_ID

# Route table pública
RT_ID=$(aws ec2 create-route-table \
  --vpc-id $VPC_ID \
  --tag-specifications 'ResourceType=route-table,Tags=[{Key=Name,Value=rt-pedidos360-public}]' \
  --query 'RouteTable.RouteTableId' --output text)
aws ec2 create-route --route-table-id $RT_ID --destination-cidr-block 0.0.0.0/0 --gateway-id $IGW_ID
aws ec2 associate-route-table --subnet-id $SUBNET_ID --route-table-id $RT_ID

echo "VPC=$VPC_ID SUBNET=$SUBNET_ID IGW=$IGW_ID RT=$RT_ID"
```

> Si prefieren alta disponibilidad, creen una segunda subred en `us-east-1b` y una segunda ruta.
> Para la demo con 4 instancias, una sola subred es suficiente.

### 3.2 IPs privadas fijas (recomendado)

Al lanzar cada EC2 asignen IP privada fija dentro de `10.0.1.0/24`:

| Instancia | IP privada |
|---|---|
| frontend | `10.0.1.10` |
| backend-1 | `10.0.1.11` |
| backend-2 | `10.0.1.12` |
| backend-3 | `10.0.1.13` |

Así los `.env` y `nginx.conf` no cambian si reinician las instancias.

---

## 4. Security Groups (reglas exactas)

Crear 4 grupos en la misma VPC. **Regla de oro:** ningún puerto de backend se abre a `0.0.0.0/0`.

Primero guarden su IP pública actual (para SSH):

```bash
MI_IP=$(curl -s https://checkip.amazonaws.com)/32
echo $MI_IP
```

### 4.1 `sg-pedidos360-frontend`

| Dirección | Protocolo | Puerto | Origen/Destino | Para qué |
|---|---|---|---|---|
| Entrada | TCP | 22 | `MI_IP` | SSH |
| Entrada | TCP | 80 | `0.0.0.0/0` | HTTP público |
| Entrada | TCP | 443 | `0.0.0.0/0` | HTTPS (si agregan TLS) |
| Salida | Todos | Todos | `0.0.0.0/0` | Salida general |

### 4.2 `sg-pedidos360-backend1`

| Dirección | Protocolo | Puerto | Origen | Para qué |
|---|---|---|---|---|
| Entrada | TCP | 22 | `sg-pedidos360-frontend` | SSH vía bastión |
| Entrada | TCP | 8081 | `sg-pedidos360-frontend` | nginx → productos |
| Entrada | TCP | 8082 | `sg-pedidos360-frontend` | nginx → pedidos |
| Entrada | TCP | 8083 | `sg-pedidos360-frontend` | nginx → usuarios |
| Entrada | TCP | 8081 | `sg-pedidos360-backend3` | recomendaciones → productos (HTTP) |
| Salida | Todos | Todos | `0.0.0.0/0` | Kafka y salida general |

### 4.3 `sg-pedidos360-backend2`

| Dirección | Protocolo | Puerto | Origen | Para qué |
|---|---|---|---|---|
| Entrada | TCP | 22 | `sg-pedidos360-frontend` | SSH vía bastión |
| Entrada | TCP | 8084 | `sg-pedidos360-frontend` | nginx → notificaciones |
| Entrada | TCP | 8085 | `sg-pedidos360-frontend` | nginx → carrito |
| Entrada | TCP | 8086 | `sg-pedidos360-frontend` | nginx → envíos |
| Salida | Todos | Todos | `0.0.0.0/0` | Kafka y salida general |

### 4.4 `sg-pedidos360-backend3`

| Dirección | Protocolo | Puerto | Origen | Para qué |
|---|---|---|---|---|
| Entrada | TCP | 22 | `sg-pedidos360-frontend` | SSH vía bastión |
| Entrada | TCP | 8087 | `sg-pedidos360-frontend` | nginx → pagos |
| Entrada | TCP | 8088 | `sg-pedidos360-frontend` | nginx → recomendaciones |
| Entrada | TCP | 29092 | `sg-pedidos360-backend1` | Kafka desde backend-1 |
| Entrada | TCP | 29092 | `sg-pedidos360-backend2` | Kafka desde backend-2 |
| Entrada | TCP | 29092 | `sg-pedidos360-backend3` | Kafka local (mismo host) |
| Entrada | TCP | 8090 | `MI_IP` | Kafka UI (solo túnel/SSH) |
| Salida | Todos | Todos | `0.0.0.0/0` | Salida general |

### 4.5 Crear los SG por CLI

```bash
VPC_ID=<tu-vpc-id>
MI_IP=$(curl -s https://checkip.amazonaws.com)/32

crear_sg () {
  aws ec2 create-security-group --vpc-id $VPC_ID --group-name "$1" --description "$2" \
    --query 'GroupId' --output text
}

SG_FRONT=$(crear_sg sg-pedidos360-frontend "Frontend nginx")
SG_B1=$(crear_sg sg-pedidos360-backend1 "Backend-1 productos/pedidos/usuarios")
SG_B2=$(crear_sg sg-pedidos360-backend2 "Backend-2 notificaciones/carrito/envios")
SG_B3=$(crear_sg sg-pedidos360-backend3 "Backend-3 pagos/recomendaciones/Kafka")

# frontend
aws ec2 authorize-security-group-ingress --group-id $SG_FRONT --ip-permissions \
  "[{\"IpProtocol\":\"tcp\",\"FromPort\":22,\"ToPort\":22,\"IpRanges\":[{\"CidrIp\":\"$MI_IP\"}]},
    {\"IpProtocol\":\"tcp\",\"FromPort\":80,\"ToPort\":80,\"IpRanges\":[{\"CidrIp\":\"0.0.0.0/0\"}]},
    {\"IpProtocol\":\"tcp\",\"FromPort\":443,\"ToPort\":443,\"IpRanges\":[{\"CidrIp\":\"0.0.0.0/0\"}]}]"

# backend-1
aws ec2 authorize-security-group-ingress --group-id $SG_B1 --ip-permissions \
  "[{\"IpProtocol\":\"tcp\",\"FromPort\":22,\"ToPort\":22,\"UserIdGroupPairs\":[{\"GroupId\":\"$SG_FRONT\"}]},
    {\"IpProtocol\":\"tcp\",\"FromPort\":8081,\"ToPort\":8083,\"UserIdGroupPairs\":[{\"GroupId\":\"$SG_FRONT\"}]},
    {\"IpProtocol\":\"tcp\",\"FromPort\":8081,\"ToPort\":8081,\"UserIdGroupPairs\":[{\"GroupId\":\"$SG_B3\"}]}]"

# backend-2
aws ec2 authorize-security-group-ingress --group-id $SG_B2 --ip-permissions \
  "[{\"IpProtocol\":\"tcp\",\"FromPort\":22,\"ToPort\":22,\"UserIdGroupPairs\":[{\"GroupId\":\"$SG_FRONT\"}]},
    {\"IpProtocol\":\"tcp\",\"FromPort\":8084,\"ToPort\":8086,\"UserIdGroupPairs\":[{\"GroupId\":\"$SG_FRONT\"}]}]"

# backend-3
aws ec2 authorize-security-group-ingress --group-id $SG_B3 --ip-permissions \
  "[{\"IpProtocol\":\"tcp\",\"FromPort\":22,\"ToPort\":22,\"UserIdGroupPairs\":[{\"GroupId\":\"$SG_FRONT\"}]},
    {\"IpProtocol\":\"tcp\",\"FromPort\":8087,\"ToPort\":8088,\"UserIdGroupPairs\":[{\"GroupId\":\"$SG_FRONT\"}]},
    {\"IpProtocol\":\"tcp\",\"FromPort\":29092,\"ToPort\":29092,\"UserIdGroupPairs\":[{\"GroupId\":\"$SG_B1\"},{\"GroupId\":\"$SG_B2\"},{\"GroupId\":\"$SG_B3\"}]},
    {\"IpProtocol\":\"tcp\",\"FromPort\":8090,\"ToPort\":8090,\"IpRanges\":[{\"CidrIp\":\"$MI_IP\"}]}]"
```

---

## 5. Key pair y rol IAM para las instancias

```bash
# Key pair (guarden pedidos360-key.pem; en Windows usar PuttyGen si es necesario)
aws ec2 create-key-pair --key-name pedidos360-key \
  --query 'KeyMaterial' --output text > pedidos360-key.pem
chmod 400 pedidos360-key.pem   # Linux/macOS

# Rol IAM para SSM + CloudWatch (opcional pero recomendado)
cat > trust.json <<'EOF'
{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}
EOF
aws iam create-role --role-name Pedidos360EC2Role --assume-role-policy-document file://trust.json
aws iam attach-role-policy --role-name Pedidos360EC2Role \
  --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore
aws iam attach-role-policy --role-name Pedidos360EC2Role \
  --policy-arn arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy
aws iam create-instance-profile --instance-profile-name Pedidos360EC2Profile
aws iam add-role-to-instance-profile --instance-profile-name Pedidos360EC2Profile --role-name Pedidos360EC2Role
```

---

## 6. Lanzar las 4 instancias EC2

Tipo `t3.small` (2 vCPU, 2 GB), AMI **Ubuntu Server 22.04 LTS**, disco **gp3 de 20 GB**.
Las 4 usan el mismo `user-data` (instala Docker, git, swap de 2 GB y JDK 17).

### 6.1 Script de arranque (`user-data.sh`)

```bash
#!/bin/bash
set -euxo pipefail
export DEBIAN_FRONTEND=noninteractive

apt-get update -y
apt-get install -y ca-certificates curl gnupg git unzip openjdk-17-jdk-headless

# Docker oficial
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" \
  > /etc/apt/sources.list.d/docker.list
apt-get update -y
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
usermod -aG docker ubuntu
systemctl enable --now docker

# Swap 2 GB: clave para que 3 JVM + Kafka convivan en 2 GB de RAM
if [ ! -f /swapfile ]; then
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

# Ajuste de swappiness para priorizar RAM
sysctl vm.swappiness=10
echo 'vm.swappiness=10' >> /etc/sysctl.conf
```

### 6.2 Lanzar cada instancia

```bash
AMI_ID=$(aws ec2 describe-images --owners 099720109477 \
  --filters 'Name=name,Values=ubuntu/images/hvm-ssd/ubuntu-jammy-22.04-amd64-server-*' \
            'Name=state,Values=available' \
  --query 'sort_by(Images,&CreationDate)[-1].ImageId' --output text)

lanzar () {
  NOMBRE=$1; SG=$2; IP=$3
  aws ec2 run-instances \
    --image-id $AMI_ID --instance-type t3.small \
    --key-name pedidos360-key \
    --security-group-ids $SG \
    --subnet-id $SUBNET_ID \
    --private-ip-address $IP \
    --block-device-mappings 'DeviceName=/dev/sda1,Ebs={VolumeSize=20,VolumeType=gp3,DeleteOnTermination=true}' \
    --iam-instance-profile Name=Pedidos360EC2Profile \
    --user-data file://user-data.sh \
    --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$NOMBRE}]" \
    --query 'Instances[0].InstanceId' --output text
}

ID_FRONT=$(lanzar pedidos360-frontend $SG_FRONT 10.0.1.10)
ID_B1=$(lanzar pedidos360-backend1 $SG_B1 10.0.1.11)
ID_B2=$(lanzar pedidos360-backend2 $SG_B2 10.0.1.12)
ID_B3=$(lanzar pedidos360-backend3 $SG_B3 10.0.1.13)
echo "$ID_FRONT $ID_B1 $ID_B2 $ID_B3"
```

### 6.3 Elastic IP para el frontend

```bash
EIP=$(aws ec2 allocate-address --domain vpc --query 'AllocationId' --output text)
aws ec2 associate-address --instance-id $ID_FRONT --allocation-id $EIP
aws ec2 describe-addresses --allocation-ids $EIP --query 'Addresses[0].PublicIp' --output text
```

> Las IPs públicas de backend-1/2/3 cambian al detener/iniciar. Por eso **no** se usan en
> configuración: todo el tráfico interno va por IP privada (`10.0.1.x`). Para SSH usen el
> frontend como bastión o AWS Systems Manager Session Manager (el rol IAM ya lo permite).

### 6.4 SSH

```bash
# Copiar la llave al frontend
scp -i pedidos360-key.pem pedidos360-key.pem ubuntu@<EIP_FRONT>:/home/ubuntu/
ssh -i pedidos360-key.pem ubuntu@<EIP_FRONT>

# Desde el frontend, saltar a cada backend
ssh -i ~/pedidos360-key.pem ubuntu@10.0.1.11
ssh -i ~/pedidos360-key.pem ubuntu@10.0.1.12
ssh -i ~/pedidos360-key.pem ubuntu@10.0.1.13
```

Alternativa sin llaves:

```bash
aws ssm start-session --target <instance-id>
```

---

## 7. Clonar repositorios en cada instancia

```bash
# backend-1
git clone https://github.com/TU-USUARIO/Ev1CloudNative_Mountain_backend.git
cd Ev1CloudNative_Mountain_backend
cp .env.example .env    # editar variables (ver sección 9)

# backend-2
git clone https://github.com/TU-USUARIO/Ev1CloudNative_Mountain_backend_parte2.git
cd Ev1CloudNative_Mountain_backend_parte2
cp .env.example .env

# backend-3
git clone https://github.com/TU-USUARIO/Ev1CloudNative_Mountain_backend_parte3.git
cd Ev1CloudNative_Mountain_backend_parte3
cp .env.example .env

# frontend
git clone https://github.com/TU-USUARIO/Ev1CloudNative_Mountain.git
cd Ev1CloudNative_Mountain
cp .env.example .env
```

> Si los repos son privados, generen un **fine-grained token** de solo lectura y usen
> `https://<TOKEN>@github.com/...` o configuren una deploy key.

---

## 8. Levantar backend-3 primero (Kafka + pagos + recomendaciones)

Kafka debe estar arriba antes que los demás servicios para que los consumidores se conecten.

```bash
cd ~/Ev1CloudNative_Mountain_backend_parte3

# 1) Compilar los dos microservicios
./mvnw clean package -DskipTests

# 2) Levantar Kafka + Kafka UI + pagos + recomendaciones
docker compose up -d --build

# 3) Verificar
docker compose ps
docker logs -f pedidos360-kafka | head -50
```

### 8.1 Crear tópicos manualmente (evidencia para la rúbrica)

Spring Kafka los crea automáticamente si no existen, pero conviene ejecutar:

```bash
docker exec pedidos360-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --create --if-not-exists \
  --topic pedidos.creados --partitions 3 --replication-factor 1

docker exec pedidos360-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --create --if-not-exists \
  --topic pagos.procesados --partitions 3 --replication-factor 1

docker exec pedidos360-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --create --if-not-exists \
  --topic pagos.fraude.detectado --partitions 3 --replication-factor 1

# Listar
docker exec pedidos360-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --list
```

### 8.2 Kafka UI

Abrir un túnel desde su PC:

```bash
ssh -i pedidos360-key.pem -L 8090:localhost:8090 ubuntu@<EIP_FRONT>
# luego http://localhost:8090
```

> Alternativa: entrar por el frontend y desde ahí `ssh -L 8090:10.0.1.13:8090 ubuntu@10.0.1.13`.
> El SG solo permite 8090 desde su IP.

---

## 9. Variables de entorno por servicio

### 9.1 backend-1 — `.env`

```dotenv
FRONTEND_ORIGIN=http://<EIP_FRONT>
KAFKA_BOOTSTRAP_SERVERS=10.0.1.13:29092

# JWT local (mismo secreto en TODOS los servicios)
JWT_LOCAL_ENABLED=true
JWT_LOCAL_SECRET=<secreto-de-32-bytes-minimo>
JWT_LOCAL_ISSUER=pedidos360-usuarios
JWT_LOCAL_AUDIENCE=pedidos360-api

# Azure Entra ID (opcional; dejar JWT_ENABLED=false si no se usa)
JWT_ENABLED=false
JWT_JWKS_URI=
JWT_ISSUER_URI=
JWT_AUDIENCES=

# Heaps
JAVA_OPTS_PRODUCTOS=-Xms128m -Xmx320m
JAVA_OPTS_PEDIDOS=-Xms128m -Xmx320m
JAVA_OPTS_USUARIOS=-Xms128m -Xmx256m
```

### 9.2 backend-2 — `.env`

```dotenv
FRONTEND_ORIGIN=http://<EIP_FRONT>
KAFKA_BOOTSTRAP_SERVERS=10.0.1.13:29092

JWT_LOCAL_ENABLED=true
JWT_LOCAL_SECRET=<mismo-secreto>
JWT_LOCAL_ISSUER=pedidos360-usuarios
JWT_LOCAL_AUDIENCE=pedidos360-api

# SMTP: vacío = modo simulado (solo log). Para correo real, completar.
SMTP_HOST=
SMTP_PORT=587
SMTP_USER=
SMTP_PASS=
SMTP_FROM=no-reply@summitlab.cl

JAVA_OPTS_NOTIFICACIONES=-Xms96m -Xmx256m
JAVA_OPTS_CARRITO=-Xms96m -Xmx224m
JAVA_OPTS_ENVIOS=-Xms96m -Xmx256m
```

### 9.3 backend-3 — `.env`

```dotenv
FRONTEND_ORIGIN=http://<EIP_FRONT>

# Kafka: dentro del compose se usa el nombre del servicio
KAFKA_BOOTSTRAP_SERVERS=kafka:9092
KAFKA_EXTERNAL_HOST=10.0.1.13
KAFKA_HEAP_OPTS=-Xms256m -Xmx512m

# JWT local
JWT_LOCAL_ENABLED=true
JWT_LOCAL_SECRET=<mismo-secreto>
JWT_LOCAL_ISSUER=pedidos360-usuarios
JWT_LOCAL_AUDIENCE=pedidos360-api

# Recomendaciones -> productos
PRODUCTOS_URL=http://10.0.1.11:8081

# WebPay (Transbank)
# INTEGRATION con credenciales de prueba; si se deja WEBPAY_ENABLED=false usa el simulador.
WEBPAY_ENABLED=false
WEBPAY_ENV=INTEGRATION
WEBPAY_COMMERCE_CODE=
WEBPAY_API_KEY=
WEBPAY_RETURN_URL=http://<EIP_FRONT>/api/payments/webpay/return
WEBPAY_FRONTEND_URL=http://<EIP_FRONT>

# Umbrales de fraude
FRAUDE_UMBRAL_APROBADO=40
FRAUDE_UMBRAL_REVISION=70

JAVA_OPTS_PAGOS=-Xms96m -Xmx256m
JAVA_OPTS_RECOMENDACIONES=-Xms96m -Xmx256m
```

### 9.4 frontend — `.env`

```dotenv
VITE_API_BASE_URL=/api
VITE_USE_MOCK=false
VITE_JWT_ISSUERS=pedidos360-usuarios
VITE_JWT_AUDIENCES=pedidos360-api
VITE_JWT_REQUIRED_SCOPES=orders.write
```

Y en el compose del frontend (o al construir la imagen):

```dotenv
BACKEND1_IP=10.0.1.11
BACKEND2_IP=10.0.1.12
BACKEND3_IP=10.0.1.13
```

---

## 10. Levantar backend-1, backend-2 y frontend

### 10.1 backend-1

```bash
cd ~/Ev1CloudNative_Mountain_backend
./mvnw clean package -DskipTests
docker compose up -d --build
docker compose ps
```

### 10.2 backend-2

```bash
cd ~/Ev1CloudNative_Mountain_backend_parte2
./mvnw clean package -DskipTests
docker compose up -d --build
docker compose ps
```

### 10.3 frontend

```bash
cd ~/Ev1CloudNative_Mountain
# El Dockerfile compila el React y lo sirve con nginx.
docker build \
  --build-arg VITE_API_BASE_URL=/api \
  --build-arg VITE_USE_MOCK=false \
  --build-arg VITE_JWT_ISSUERS=pedidos360-usuarios \
  --build-arg VITE_JWT_AUDIENCES=pedidos360-api \
  --build-arg VITE_JWT_REQUIRED_SCOPES=orders.write \
  -t pedidos360/frontend:latest .

docker run -d --name pedidos360-frontend --restart unless-stopped \
  -p 80:80 \
  -e BACKEND1_IP=10.0.1.11 \
  -e BACKEND2_IP=10.0.1.12 \
  -e BACKEND3_IP=10.0.1.13 \
  pedidos360/frontend:latest
```

Abrir `http://<EIP_FRONT>`.

> Si usan `docker compose` para el frontend, el compose ya inyecta `BACKEND1_IP`, `BACKEND2_IP`
> y `BACKEND3_IP` en la plantilla de nginx (`nginx.conf.template` + `envsubst`).

---

## 11. Verificación end-to-end

### 11.1 Health checks

```bash
curl -s http://10.0.1.11:8081/actuator/health
curl -s http://10.0.1.11:8082/actuator/health
curl -s http://10.0.1.11:8083/actuator/health
curl -s http://10.0.1.12:8084/actuator/health
curl -s http://10.0.1.12:8085/actuator/health
curl -s http://10.0.1.12:8086/actuator/health
curl -s http://10.0.1.13:8087/actuator/health
curl -s http://10.0.1.13:8088/actuator/health
```

Todos deben responder `{"status":"UP"}`.

### 11.2 Flujo completo (desde el frontend)

```bash
# 1. Registro / login
curl -s -X POST http://localhost/api/auth/registro \
  -H 'Content-Type: application/json' \
  -d '{"nombre":"Demo","email":"demo@summitlab.cl","password":"Demo1234"}'

# Guardar el token de la respuesta y usarlo como TOKEN
TOKEN=<jwt>

# 2. Crear pedido
curl -s -X POST http://localhost/api/orders \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"items":[{"productId":1,"quantity":2}]}'

# 3. Ver stock descontado
curl -s http://localhost/api/products/1

# 4. Iniciar pago
curl -s -X POST http://localhost/api/payments \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"orderId":"PEDIDO-XXXXXX","amount":179800,"paymentMethod":"WEBPAY"}'

# 5. Confirmar pago simulado
curl -s -X POST http://localhost/api/payments/<paymentId>/confirm \
  -H "Authorization: Bearer $TOKEN"

# 6. Ver pedido PAGADO
curl -s http://localhost/api/orders/PEDIDO-XXXXXX -H "Authorization: Bearer $TOKEN"

# 7. Ver envío creado
curl -s http://localhost/api/shipments -H "Authorization: Bearer $TOKEN"

# 8. Recomendaciones
curl -s "http://localhost/api/recommendations?email=demo@summitlab.cl" \
  -H "Authorization: Bearer $TOKEN"
```

### 11.3 Evidencia Kafka

```bash
# Listar tópicos
docker exec pedidos360-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --list

# Ver mensajes de pedidos
docker exec -it pedidos360-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic pedidos.creados --from-beginning

# Consumer groups activos
docker exec pedidos360-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --list

# Detalle de offsets
docker exec pedidos360-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group productos-stock
```

### 11.4 Caso de fraude

```bash
curl -s -X POST http://localhost/api/payments \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"orderId":"PEDIDO-XXXXXX","amount":99999999,"paymentMethod":"WEBPAY"}'
# Debe responder riskLevel=FRAUDE y publicar en pagos.fraude.detectado
```

---

## 12. API Gateway (opcional, recomendado para la rúbrica)

La arquitectura declara AWS API Gateway como entrada. Si el tiempo alcanza:

1. API Gateway → **Create API** → **HTTP API**.
2. Rutas:
   - `ANY /api/products/{proxy+}` → `http://10.0.1.11:8081/{proxy}`
   - `ANY /api/orders/{proxy+}` → `http://10.0.1.11:8082/{proxy}`
   - `ANY /api/auth/{proxy+}` → `http://10.0.1.11:8083/{proxy}`
   - `ANY /api/notifications/{proxy+}` → `http://10.0.1.12:8084/{proxy}`
   - `ANY /api/cart/{proxy+}` → `http://10.0.1.12:8085/{proxy}`
   - `ANY /api/shipments/{proxy+}` → `http://10.0.1.12:8086/{proxy}`
   - `ANY /api/payments/{proxy+}` → `http://10.0.1.13:8087/{proxy}`
   - `ANY /api/recommendations/{proxy+}` → `http://10.0.1.13:8088/{proxy}`
3. Integración: **HTTP_PROXY** a las IPs privadas (requiere VPC Link o ALB si es HTTP API privado).
4. Autorizador JWT con el issuer/audience de Azure Entra ID (opcional).
5. CORS: permitir el origen del frontend.

> **Alternativa más simple y ya implementada:** nginx en el frontend hace el proxy a las IPs
> privadas. API Gateway es un extra; si se agrega, hay que apuntar el frontend al endpoint
> del gateway y quitar el proxy de nginx (o dejar nginx como fallback).

---

## 13. Observabilidad y operación

### 13.1 Logs

```bash
docker compose logs -f --tail=200
docker logs -f pedidos360-kafka
```

### 13.2 CloudWatch (opcional)

Instalar el agente con el rol IAM ya creado:

```bash
sudo apt-get install -y amazon-cloudwatch-agent
sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-config-wizard
sudo systemctl enable --now amazon-cloudwatch-agent
```

### 13.3 Reinicio automático

Todos los servicios usan `restart: unless-stopped`. Si una instancia se reinicia, Docker
levanta los contenedores solos.

### 13.4 Apagar todo (ahorro)

```bash
aws ec2 stop-instances --instance-ids $ID_FRONT $ID_B1 $ID_B2 $ID_B3
aws ec2 start-instances --instance-ids $ID_FRONT $ID_B1 $ID_B2 $ID_B3
```

---

## 14. Troubleshooting

| Síntoma | Causa probable | Solución |
|---|---|---|
| `connection refused` a 8081-8088 desde nginx | SG no permite el puerto o servicio caído | Revisar SG y `docker compose ps` |
| Consumidores Kafka no reciben | `KAFKA_BOOTSTRAP_SERVERS` mal o listener externo no anuncia la IP correcta | Debe ser `10.0.1.13:29092` en backend-1/2 y `kafka:9092` en backend-3 |
| `NoBrokersAvailable` | Kafka aún arrancando | Esperar 30–60 s; `docker logs pedidos360-kafka` |
| OOM / contenedor reiniciándose | Heaps muy altos para 2 GB | Bajar `-Xmx`, verificar swap activo (`free -h`) |
| `502 Bad Gateway` en nginx | IP privada incorrecta en `BACKENDx_IP` | Revisar `docker exec pedidos360-frontend env | grep BACKEND` |
| `401` en `/orders` | JWT no enviado o secreto distinto | Mismo `JWT_LOCAL_SECRET` en los 3 backends |
| WebPay devuelve error | `WEBPAY_ENABLED=true` sin credenciales | Dejar `false` para usar el simulador |
| Kafka UI no abre | Túnel SSH mal armado | `ssh -L 8090:10.0.1.13:8090 ubuntu@10.0.1.10` |
| Disco lleno | Logs/imágenes Docker | `docker system prune -af` |

---

## 15. Checklist final para la entrega

- [ ] Cuenta AWS con MFA y usuario admin (no root).
- [ ] VPC `10.0.0.0/16`, subred pública, IGW y route table.
- [ ] 4 security groups con reglas exactas de la sección 4.
- [ ] 4 instancias `t3.small` con IPs privadas fijas `10.0.1.10`–`10.0.1.13`.
- [ ] Elastic IP en el frontend.
- [ ] Docker + compose instalados en las 4 instancias.
- [ ] Swap de 2 GB activo en las 4 instancias (`free -h`).
- [ ] Kafka arriba en backend-3 y los 3 tópicos creados.
- [ ] Kafka UI accesible por túnel (evidencia).
- [ ] 8 microservicios con `/actuator/health` en `UP`.
- [ ] Frontend accesible por la Elastic IP.
- [ ] Flujo pedido → pago → PAGADO → envío → notificación → recomendaciones.
- [ ] Caso de fraude genera `pagos.fraude.detectado`.
- [ ] `401` sin token en `/orders` y `/payments`.
- [ ] Capturas: `docker compose ps`, tópicos, consumer groups, logs, respuestas JSON.
- [ ] Variables AWS/Azure documentadas (no es necesario implementar Azure si no se usa).

---

## 16. Referencia rápida de comandos

```bash
# Estado de contenedores
docker compose ps
docker compose logs -f --tail=100

# Reconstruir un servicio
docker compose up -d --build servicio-pagos

# Kafka
docker exec pedidos360-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
docker exec pedidos360-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --list

# Health de todo el sistema (desde el frontend)
for p in 8081 8082 8083; do curl -s http://10.0.1.11:$p/actuator/health; echo; done
for p in 8084 8085 8086; do curl -s http://10.0.1.12:$p/actuator/health; echo; done
for p in 8087 8088; do curl -s http://10.0.1.13:$p/actuator/health; echo; done

# Espacio y memoria
df -h
free -h
docker stats --no-stream
```
