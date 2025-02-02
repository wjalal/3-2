package fileserver;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;


public class ReadThreadServer implements Runnable {
    private Thread thr;
    private NetworkUtil networkUtil;
    public HashMap<String, User> clientMap;
    final long MAX_BUFFER_SIZE = 102400000;
    final long MIN_CHUNK_SIZE = 1024;
    final long MAX_CHUNK_SIZE = 102400;
    long bufferSize = 0;
    HashMap<Long, FileInfo> fileMap = new HashMap<Long, FileInfo>();
    Hashtable<Long, String> reqMap = new Hashtable<Long, String>();

    public ReadThreadServer(HashMap<String, User> map, NetworkUtil networkUtil) {
        this.clientMap = map;
        this.networkUtil = networkUtil;
        this.thr = new Thread(this);
        thr.start();
    }

    public void run() {
        try {
            while (true) {
                System.out.println("Buffer: " + bufferSize + "/" + MAX_BUFFER_SIZE + " bytes");
                // clientMap.forEach((name, user) -> {
                //     try {
                //         user.getNetworkUtil().write("ALIVE?");
                //         //user.setOnline(true);
                //     } catch (IOException e) {
                //         user.setOnline(false);
                //     }
                // });
                Object o = networkUtil.read();
                if (o instanceof String) {
                    String clientName = (String) o;
                    User u = clientMap.get(clientName);
                    if (u != null) {
                        if (u.isOnline()) {
                            networkUtil.write(new InvalidUsernameWarning());
                            networkUtil.closeConnection();
                        } else {
                            u.setNetworkUtil(networkUtil);
                            u.setOnline(true);
                        }
                    } else {
                        u = new User(networkUtil);
                        clientMap.put(clientName, u);
                        if ((new File("users/" + clientName + "/public")).mkdirs()) {
                            System.out.println("User directory created for " + clientName);
                        } else {
                            System.out.println("User directory could not be created for " + clientName);
                        }
                        if ((new File("users/" + clientName + "/private")).mkdirs()) {
                            System.out.println("User directory created for " + clientName);
                        } else {
                            System.out.println("User directory could not be created for " + clientName);
                        }
                    }
                    networkUtil.write (u.getInbox());
                } else if (o instanceof Message) {
                    Message obj = (Message) o;
                    String to = obj.getTo();
                    User u = clientMap.get(to);
                    if (u.isOnline() && u.getNetworkUtil() != null) {
                        u.getNetworkUtil().write(obj);
                    }
                    u.addUnreadMessage(obj);
                } else if (o instanceof UserListRequest) {
                    UserListRequest req = (UserListRequest) o;
                    String from = req.getFrom();
                    System.out.println("received user list request from " + from);
                    NetworkUtil nu = clientMap.get(from).getNetworkUtil();
                    if (nu != null) {
                        nu.write(new UserList(clientMap, networkUtil));
                    }
                } else if (o instanceof FileInfo) {
                    FileInfo fd = (FileInfo) o;
                    //Path filePath = Paths.get("users/" + fd.getOwnerName() + (fd.ifPublic? "/public/" : "/private/") + fd.getName());
                    //Files.write (filePath, fd.getBytes());
                    System.out.println("Received file upload from " + fd.getOwnerName());
                    System.out.println("Name: " + fd.getName());
                    System.out.println("Size: " + fd.getFileSize() + " bytes");
                    if (fd.getReqID() != -1) System.out.println("ReqID: " + fd.getReqID() );
                    fd.setFileID(ThreadLocalRandom.current().nextLong(1024, Long.MAX_VALUE));
                    System.out.println("Generated file ID: " + fd.getFileID());
                    if (bufferSize + fd.getFileSize() <= MAX_BUFFER_SIZE) {
                        fd.setChunkSize(ThreadLocalRandom.current().nextLong(MIN_CHUNK_SIZE, MAX_CHUNK_SIZE+1));
                        System.out.println("Generated chunk size: " + fd.getChunkSize());
                        networkUtil.write(fd);
                        fileMap.put(fd.getFileID(), fd);
                    } else {
                        System.out.println("Insufficient bufferspace");
                        networkUtil.write (new UploadFailureAck(fd.getFileID()));
                    }
                } else if (o instanceof UserFileRequest) {
                    UserFileRequest req = (UserFileRequest) o;
                    User u = clientMap.get(req.username);
                    FileList list = new FileList(req.username);
                    if (req.getClientAddress().equals(u.getNetworkUtil().getSocket().getRemoteSocketAddress())) {
                        System.out.println("Private file list request");
                        File[] files = (new File ("users/" + req.username + "/private")).listFiles();
                        for (File file : files) {
                            if (file.isFile()) {
                                list.addPrivate((file.getName().toString()));
                            }
                        }
                    } else {
                        System.out.println("Public file list request");
                    }
                    File[] files = (new File ("users/" + req.username + "/public")).listFiles();
                    for (File file : files) {
                        if (file.isFile()) {
                            list.addPublic((file.getName().toString()));
                        }
                    }  
                    networkUtil.write(list);
                } else if (o instanceof FileChunk) {
                    FileChunk fc = (FileChunk) o;
                    bufferSize += fc.getChunkSize();
                    (new File("chunkbuffer")).mkdirs();
                    Path chPath = Paths.get("chunkbuffer/.chunk_" + fc.getChunkOrder() + "_" + fc.getFileID());
                    Files.write (chPath, fc.getData());
                    networkUtil.write (new ChunkAck(fc.getFileID(), fc.getChunkOrder()));
                } else if (o instanceof UploadCompletionAck) {        
                    UploadCompletionAck ack = (UploadCompletionAck) o;
                    System.out.println("All chunks uploaded by client for " + ack.getFileID());
                    FileInfo fd = fileMap.get(ack.getFileID());
                    Path filePath = Paths.get("users/" + fd.getOwnerName() + (fd.ifPublic? "/public/" : "/private/") + fd.getName());
                    long cs = fd.getChunkSize(), fs = fd.getFileSize();
                    long nChunk = (fs + cs - 1) / cs;
                    long chunkTotal = 0;
                    for (int i=0; i<nChunk; i++) {
                        Path cPath = Paths.get("chunkbuffer/.chunk_" + i + "_" + ack.getFileID());
                        byte[] bytes = Files.readAllBytes(cPath);
                        chunkTotal += Files.size(cPath);
                        if (i==0) Files.write (filePath, bytes);
                        else Files.write (filePath, bytes, StandardOpenOption.APPEND);
                        Files.delete(cPath);
                    }
                    if (chunkTotal == fs) {
                        System.out.println(filePath.getFileName() + " upload succeded, sending success message");
                        networkUtil.write (new UploadSuccessAck(ack.getFileID()));
                        if (fd.getReqID() != -1) {
                            System.out.println("ReqID: " + fd.getReqID());
                            String requesterName = reqMap.get(fd.getReqID());
                            System.out.println("Requested by: " + requesterName);
                            User requester = clientMap.get(requesterName);
                            if (requester != null) {
                                Message m = new Message();
                                m.setFrom("Server");
                                m.setTo(requesterName);
                                m.setText("\n"+ fd.getOwnerName() + " has responded to your request: " 
                                             + " [Req ID: " + fd.getReqID() + "]");
                                try {
                                    if (requester.isOnline() && requester.getNetworkUtil() != null) requester.getNetworkUtil().write(m);
                                    requester.addUnreadMessage(m);
                                } catch (IOException e) {
                                    System.out.println(e.getMessage());
                                }
                            }
                        }
                    } else {
                        System.out.println(filePath.getFileName() + " upload failed, sending failure message");
                        networkUtil.write (new UploadFailureAck(ack.getFileID()));
                    }
                } else if (o instanceof FileDownloadRequest) {
                    FileDownloadRequest req = (FileDownloadRequest) o;
                    User u = clientMap.get(req.username);
                    File f;
                    if (req.getClientAddress().equals(u.getNetworkUtil().getSocket().getRemoteSocketAddress())) {
                        System.out.println("Private file request");
                        f = (new File ("users/" + req.getUsername() + "/private/" + req.getFilename()));
                        if (!f.exists() || f.isDirectory()) {
                            f = (new File ("users/" + req.getUsername() + "/public/" + req.getFilename()));
                        }
                    } else {
                        System.out.println("Public file list request");
                        f = (new File ("users/" + req.getUsername() + "/public/" + req.getFilename()));
                    }
                    if (f.exists() && !f.isDirectory()) {
                        long cs = MAX_CHUNK_SIZE, fs = f.length();
                        long nChunk = (fs + cs - 1) / cs;
                        System.out.println("Creating " + nChunk + " chunks");  
                        byte[] bytes = Files.readAllBytes(Paths.get(f.getPath()));
                        long id = ThreadLocalRandom.current().nextLong(1024, Long.MAX_VALUE);
                        for (int i=0; i<nChunk; i++) {
                            System.out.println("Sending chunk " + i + " of " + id);
                            byte[] cBytes;
                            if ((i+1)*cs > fs) {
                                cBytes = new byte[(int)(fs%cs)];
                                System.arraycopy(bytes, (int)(i*cs), cBytes, 0, (int)(fs%cs));
                            } else {
                                cBytes = new byte[(int)cs];
                                System.arraycopy(bytes, (int)(i*cs), cBytes, 0, (int)cs);
                            }
                            // Path chPath = Paths.get(".chunk_" + i + "_" + fi.getFileID());
                            // Files.write (chPath, cBytes);
                            FileChunk fc = new FileChunk(id, cBytes, (int)cs, i);
                            fc.setFileName(f.getName());
                            networkUtil.write(fc);
                            if (i == nChunk-1) networkUtil.write(new DownloadCompletionAck(id));
                        }
                    }
                } else if (o instanceof FileUploadRequest) {
                    FileUploadRequest req = (FileUploadRequest) o;
                    System.out.println(req.getReqID());
                    reqMap.put(req.getReqID(), req.getFrom());
                    reqMap.forEach((id, r) -> System.out.println(id + " " + r));
                    clientMap.forEach((name, u) -> {
                        Message m = new Message();
                        m.setFrom("Server");
                        m.setTo(name);
                        m.setText("\nFile upload request from " + req.getFrom() + ": " 
                                    + req.getDescription() + " [Req ID: " + req.getReqID() + "]");
                        try {
                            if (u.isOnline() && u.getNetworkUtil() != null) u.getNetworkUtil().write(m);
                            u.addUnreadMessage(m);
                        } catch (IOException e) {
                            System.out.println(e.getMessage());
                        }
                        System.out.println("Forwarded request to " + name);
                    });
                }
            }
        } catch (Exception e) {
            System.out.println(e);
        } finally {
            try {
                networkUtil.closeConnection();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}



