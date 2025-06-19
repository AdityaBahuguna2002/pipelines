pipeline {
    agent any

    parameters {
        booleanParam(
            name: 'PRUNE_UNUSED',
            defaultValue: false,
            description: 'Delete all unused Docker images (including <none> and untagged)'
        )
    }

    environment {
        IMAGE_NAME = "resourcing-tool-backend"
        IMAGE_REPO = "adityabahuguna2002"
    }

    stages {

        stage('Clean workspace') {
            steps {
                echo 'Cleaning workspace'
                cleanWs()
            }
        }

        stage('Code checkout from GitHub') {
            steps {
                echo 'Checking out code from GitHub'
                checkout scmGit(branches: [[name: '*/development']], extensions: [], userRemoteConfigs: [[credentialsId: 'github-token', url: 'https://github.com/GauravCynoteck/Resourcing_tool_Backend.git']])
            }
        }

        stage("Build with Gradle") {
            steps {
                echo "Building with Gradle"
                sh '''
                    chmod +x ./gradlew
                    ./gradlew clean build --warning-mode all
                '''
            }
        }

        stage('Trivy Full Project Scan') {
            steps {
                echo "Running full Trivy scan"
                sh '''
                    trivy fs . \
                        --scanners vuln,secret,misconfig,license \
                        --severity LOW,MEDIUM,HIGH,CRITICAL \
                        --format table \
                        --output trivy-full-scan-report.txt \
                        --no-progress \
                        --include-dev-deps \

                    trivy fs . \
                        --scanners vuln,secret,config,license \
                        --severity LOW,MEDIUM,HIGH,CRITICAL \
                        --format json \
                        --output trivy-full-scan-report.json \
                        --no-progress \
                        --include-dev-deps \
                        --list-all-pkgs
                '''
            }
        }

        stage("Create app.env") {
            steps {
                echo "Creating app.env file securely"
                withCredentials([
                    string(credentialsId: 'DB_USER_NAME', variable: 'DB_USER_NAME'),
                    string(credentialsId: 'DB_PASSWORD', variable: 'DB_PASSWORD'),
                    string(credentialsId: 'MYSQL_ROOT_PASSWORD', variable: 'MYSQL_ROOT_PASSWORD'),
                    string(credentialsId: 'MYSQL_DATABASE', variable: 'MYSQL_DATABASE')
                ]) {
                    sh '''
                        cat <<EOF > app.env
SPRING_PROFILE=dev
SCHEMA_UPDATE=update
DATABASE_HOST_STRING=cynide-mysql:3306
DATABASE_NAME=${MYSQL_DATABASE}
DB_USER_NAME=${DB_USER_NAME}
DB_PASSWORD=${DB_PASSWORD}
MYSQL_ROOT_PASSWORD=${MYSQL_ROOT_PASSWORD}
MYSQL_DATABASE=${MYSQL_DATABASE}
EOF
                        echo "--- Verifying app.env (without secrets) ---"
                        grep -v PASSWORD app.env
                    '''
                }
            }
        }

        stage("Docker Build & Cleanup") {
            steps {
                echo "Building Docker image and cleaning up old ones"
                script {
                    def imageTag = "${IMAGE_REPO}/${IMAGE_NAME}:${BUILD_NUMBER}"

                    // Build the Docker image
                    sh "docker build -t ${imageTag} ."

                    // Keep only the latest 3 tagged images and delete older ones
                    sh """
                        echo "Cleaning up old tagged Docker images for ${IMAGE_REPO}/${IMAGE_NAME}..."
                        docker images --format '{{.Repository}}:{{.Tag}} {{.CreatedAt}}' | \
                        grep '^${IMAGE_REPO}/${IMAGE_NAME}:' | \
                        sort -r | \
                        awk '{print \$1}' | \
                        sed -n '4,\$p' | \
                        xargs -r docker rmi || true
                    """

                    // Delete only dangling <none> images
                    sh """
                        echo "Removing dangling <none> images..."
                        docker images -f "dangling=true" -q | xargs -r docker rmi || true
                    """

                    // Optional: prune unused images EXCLUDING your important repo
                    if (params.PRUNE_UNUSED) {
                        echo "Skipping full 'docker image prune -a' to preserve tagged images."
                        echo "Only dangling and old tagged images are removed."
                    } else {
                        echo "Skipping full prune. Only old/tagged and dangling images removed."
                    }
                }
            }
        }



        stage("Docker Compose Deploy") {
            steps {
                echo "Deploying with Docker Compose"
                sh '''
                    echo "creating a directory if it doesn't exist..."
                    mkdir -p /var/lib/jenkins/cyno-resource/ 

                    echo "Using app.env variables for docker-compose..."
                    docker compose --env-file app.env down || true
                    docker compose --env-file app.env up -d --build
                '''
            }
        }
    }

    post {
        success {
            echo "Pipeline completed successfully"
            archiveArtifacts artifacts: 'trivy-full-scan-report.txt, trivy-full-scan-report.json', onlyIfSuccessful: true
        }
        failure {
            echo "Pipeline failed"
        }
    }
}
