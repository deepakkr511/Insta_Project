from random import random
import shutil
from typing import List
from auth.oauth2 import get_current_user
from fastapi.exceptions import HTTPException
from fastapi import APIRouter, Depends, File,status,UploadFile,File
from sqlalchemy.orm.session import Session
from auth.oauth2 import get_current_user
from routers.schemas import PostBase, PostDisplay
from db.database import get_db
from db import db_post
import random,string
from routers.schemas import UserAuth

image_url_type=['absolute','relative'] #these are the only value we accept
router= APIRouter(
    prefix='/psot',
    tags=['post']
)

@router.post('',response_model=PostDisplay)
def create(request:PostBase,db: Session=Depends(get_db),current_user:UserAuth=Depends(get_current_user)):
    if not request.image_url_type in image_url_type:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="parameter image_url_types can only take values 'absolute' or 'relative'. " )
    return db_post.create(db,request)

@router.get('/all',response_model= List[PostDisplay])
def posts(db: Session=Depends(get_db)):
    return db_post.get_all(db) #to get all the post

@router.post('/image')
def upload_image(image: UploadFile=File(...),current_user:UserAuth=Depends(get_current_user)):
    #we are simply adding some random name to the file name so that there won't be any duplicate copy in images folder
    letter = string.ascii_letters#Return all ASCII letters (both lower and upper case),abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ
    rand_str=''.join(random.choice(letter) for i in range(6))#The choice() method returns a randomly selected element from the specified sequence.
    new = f'_{rand_str}.'
    filename=new.join(image.filename.rsplit('.',1))
    path = f'images/{filename}'

    with open(path,"w+b") as buffer: #opening the file provided
        shutil.copyfileobj(image.file,buffer) #dumping into the place we have decided to put images
    
    return {'filename':path}

@router.get('/delete/{id}')
def delete(id: int,db: Session=Depends(get_db),current_user: UserAuth =Depends(get_current_user)):
    return db_post.delete(id,db,current_user.id)